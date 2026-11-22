package com.omero.auth.service;

import com.omero.auth.dto.RegisterRequest;
import com.omero.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RegistrationOtpService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationOtpService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(45);

    private final UserRepository userRepository;
    private final RegistrationService registrationService;
    private final TurnstileService turnstileService;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final String mailFrom;
    private final String smtpPassword;
    private final String frontendBaseUrl;
    private final String welcomeHtml;
    private final String otpHtml;
    private final Duration ttl;
    private static final String REDIS_PREFIX = "regotp:";
    private final ConcurrentHashMap<String, Pending> memory = new ConcurrentHashMap<>();
    private StringRedisTemplate redis;
    private ObjectMapper objectMapper;

    public RegistrationOtpService(
            UserRepository userRepository,
            RegistrationService registrationService,
            TurnstileService turnstileService,
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${app.mail-from:}") String mailFrom,
            @Value("${spring.mail.password:}") String smtpPassword,
            @Value("${app.frontend-base-url:https://telemaco.app}") String frontendBaseUrl,
            @Value("${app.registration-otp-ttl-minutes:10}") int ttlMinutes) {
        this.userRepository = userRepository;
        this.registrationService = registrationService;
        this.turnstileService = turnstileService;
        this.mailSender = mailSender;
        this.mailFrom = mailFrom == null ? "" : mailFrom.trim();
        this.smtpPassword = smtpPassword == null ? "" : smtpPassword.trim();
        String base = frontendBaseUrl == null ? "https://telemaco.app" : frontendBaseUrl.trim();
        this.frontendBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.welcomeHtml = loadClasspathHtml("/templates/welcome-email.html");
        this.otpHtml = loadClasspathHtml("/templates/otp-email.html");
        this.ttl = Duration.ofMinutes(Math.max(1, ttlMinutes));
    }

    /**
     * Optional Redis (omero.redis.enabled). Without it, pending OTPs stay in this JVM
     * and are lost on restart / a second replica.
     */
    @Autowired(required = false)
    void setRedisStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public boolean emailRegistered(String rawEmail) {
        return userRepository.existsByEmailIgnoreCase(RegistrationService.normalizeEmail(rawEmail));
    }

    public void start(RegisterRequest request) {
        if (!turnstileService.verify(request.getTurnstileToken())) {
            throw new IllegalArgumentException("Security verification failed");
        }
        validatePasswords(request);
        String email = RegistrationService.normalizeEmail(request.getEmail());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("Email already registered");
        }
        issue(email, copy(request), false);
    }

    public void resend(String rawEmail) {
        String email = RegistrationService.normalizeEmail(rawEmail);
        Pending current = getPending(email);
        if (current == null || current.expires.isBefore(Instant.now())) {
            removePending(email);
            throw new IllegalArgumentException("Invalid request");
        }
        issue(email, current.request, true);
    }

    public RegistrationService.Result verify(String rawEmail, String code) {
        String email = RegistrationService.normalizeEmail(rawEmail);
        Pending current = getPending(email);
        if (current == null || current.expires.isBefore(Instant.now())) {
            removePending(email);
            throw new IllegalArgumentException("Invalid code");
        }
        if (current.attempts >= MAX_ATTEMPTS) {
            removePending(email);
            throw new IllegalArgumentException("Too many attempts");
        }
        current.attempts++;
        if (code == null || !MessageDigest.isEqual(sha256(code.trim()), current.otpHash)) {
            putPending(email, current);
            throw new IllegalArgumentException("Invalid code");
        }
        removePending(email);
        RegistrationService.Result result = registrationService.registerCustomer(current.request);
        sendWelcomeMail(email, current.request);
        return result;
    }

    private void issue(String email, RegisterRequest request, boolean resend) {
        Pending existing = getPending(email);
        Instant now = Instant.now();
        if (resend && existing != null && existing.lastSent.plus(RESEND_COOLDOWN).isAfter(now)) {
            throw new IllegalArgumentException("Invalid request");
        }
        String code = String.format(Locale.ROOT, "%06d", RNG.nextInt(1_000_000));
        Instant expires = now.plus(ttl);
        sendMail(email, code, request.getCountry(), expires);
        putPending(email, new Pending(request, sha256(code), expires, now, 0));
    }

    private void sendWelcomeMail(String to, RegisterRequest request) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || mailFrom.isBlank() || smtpPassword.isBlank() || welcomeHtml == null) {
            log.warn("Welcome mail skipped: SMTP or template not ready");
            return;
        }
        try {
            WelcomeEmailI18n i18n = WelcomeEmailI18n.fromCountry(request.getCountry());
            String vertical = TenantVerticals.normalizeVertical(request.getVertical());
            String pricing = SectorWelcomeMail.html(
                    i18n, vertical, request.getOnboardingProfile(), frontendBaseUrl);
            String html = i18n.apply(welcomeHtml, frontendBaseUrl + "/dashboard", pricing);
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, "UTF-8");
            helper.setFrom(mailFrom);
            helper.setTo(to);
            helper.setSubject(i18n.subject());
            helper.setText(html, true);
            sender.send(mime);
        } catch (Exception e) {
            log.warn("Welcome mail failed: {}", e.getClass().getSimpleName());
        }
    }

    private static String loadClasspathHtml(String path) {
        try (InputStream in = RegistrationOtpService.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private void sendMail(String to, String code, String country, Instant expires) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || mailFrom.isBlank() || smtpPassword.isBlank()) {
            log.warn("SMTP not configured; registration OTP not sent");
            throw new IllegalStateException("Service unavailable");
        }
        OtpEmailI18n i18n = OtpEmailI18n.fromCountry(country);
        try {
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, StandardCharsets.UTF_8.name());
            helper.setFrom(mailFrom);
            helper.setTo(to);
            helper.setSubject(i18n.subject());
            String html = otpHtml == null || otpHtml.isBlank()
                    ? i18n.apply(
                            "<html><body><p>{{HEADING}}</p><p>{{INTRO}}</p><p>{{CODE}}</p><p>{{EXPIRY_NOTE}}</p></body></html>",
                            code, expires, ttl.toMinutes(), frontendBaseUrl)
                    : i18n.apply(otpHtml, code, expires, ttl.toMinutes(), frontendBaseUrl);
            helper.setText(html, true);
            sender.send(mime);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Registration OTP mail failed: {}", e.getClass().getSimpleName());
            throw new IllegalStateException("Service unavailable");
        }
    }

    static void validatePasswords(RegisterRequest request) {
        if (request.getPassword() == null || request.getPassword().length() < 6) {
            throw new IllegalArgumentException("Password too short");
        }
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("Passwords do not match");
        }
    }

    private static RegisterRequest copy(RegisterRequest src) {
        return RegisterRequest.builder()
                .firstName(src.getFirstName())
                .lastName(src.getLastName())
                .email(src.getEmail())
                .password(src.getPassword())
                .confirmPassword(src.getConfirmPassword())
                .vertical(src.getVertical())
                .companyName(src.getCompanyName())
                .bookingNotificationEmail(src.getBookingNotificationEmail())
                .vatNumber(src.getVatNumber())
                .country(src.getCountry())
                .referralCode(src.getReferralCode())
                .onboardingProfile(src.getOnboardingProfile())
                .dpaAccepted(src.isDpaAccepted())
                .build();
    }

    private Pending getPending(String email) {
        if (redis != null && objectMapper != null) {
            try {
                String raw = redis.opsForValue().get(REDIS_PREFIX + email);
                if (raw == null || raw.isBlank()) {
                    return null;
                }
                return objectMapper.readValue(raw, Pending.class);
            } catch (Exception e) {
                log.warn("OTP redis get failed");
                return memory.get(email);
            }
        }
        return memory.get(email);
    }

    private void putPending(String email, Pending pending) {
        if (redis == null || objectMapper == null) {
            memory.put(email, pending);
            return;
        }
        try {
            Duration left = Duration.between(Instant.now(), pending.expires);
            if (left.isNegative() || left.isZero()) {
                redis.delete(REDIS_PREFIX + email);
                memory.remove(email);
                return;
            }
            redis.opsForValue().set(REDIS_PREFIX + email, objectMapper.writeValueAsString(pending), left);
            memory.remove(email);
        } catch (Exception e) {
            log.warn("OTP redis put failed");
            memory.put(email, pending);
        }
    }

    private void removePending(String email) {
        memory.remove(email);
        if (redis != null) {
            try {
                redis.delete(REDIS_PREFIX + email);
            } catch (Exception e) {
                log.warn("OTP redis del failed");
            }
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Service unavailable");
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static final class Pending {
        public RegisterRequest request;
        public byte[] otpHash;
        public Instant expires;
        public Instant lastSent;
        public int attempts;

        Pending() {}

        private Pending(RegisterRequest request, byte[] otpHash, Instant expires, Instant lastSent, int attempts) {
            this.request = request;
            this.otpHash = otpHash;
            this.expires = expires;
            this.lastSent = lastSent;
            this.attempts = attempts;
        }
    }
}
