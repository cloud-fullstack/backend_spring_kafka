package com.omero.auth.service;

import com.omero.auth.dto.RegisterRequest;
import com.omero.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
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
    private final ObjectProvider<JavaMailSender> mailSender;
    private final String mailFrom;
    private final String smtpPassword;
    private final String frontendBaseUrl;
    private final String welcomeHtml;
    private final Duration ttl;
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    public RegistrationOtpService(
            UserRepository userRepository,
            RegistrationService registrationService,
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${app.mail-from:}") String mailFrom,
            @Value("${spring.mail.password:}") String smtpPassword,
            @Value("${app.frontend-base-url:https://telemaco.app}") String frontendBaseUrl,
            @Value("${app.registration-otp-ttl-minutes:10}") int ttlMinutes) {
        this.userRepository = userRepository;
        this.registrationService = registrationService;
        this.mailSender = mailSender;
        this.mailFrom = mailFrom == null ? "" : mailFrom.trim();
        this.smtpPassword = smtpPassword == null ? "" : smtpPassword.trim();
        String base = frontendBaseUrl == null ? "https://telemaco.app" : frontendBaseUrl.trim();
        this.frontendBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.welcomeHtml = loadWelcomeHtml();
        this.ttl = Duration.ofMinutes(Math.max(1, ttlMinutes));
    }

    public boolean emailRegistered(String rawEmail) {
        return userRepository.existsByEmailIgnoreCase(RegistrationService.normalizeEmail(rawEmail));
    }

    public void start(RegisterRequest request) {
        validatePasswords(request);
        String email = RegistrationService.normalizeEmail(request.getEmail());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("Email already registered");
        }
        issue(email, copy(request), false);
    }

    public void resend(String rawEmail) {
        String email = RegistrationService.normalizeEmail(rawEmail);
        Pending current = pending.get(email);
        if (current == null || current.expires.isBefore(Instant.now())) {
            pending.remove(email);
            throw new IllegalArgumentException("Invalid request");
        }
        issue(email, current.request, true);
    }

    public RegistrationService.Result verify(String rawEmail, String code) {
        String email = RegistrationService.normalizeEmail(rawEmail);
        Pending current = pending.get(email);
        if (current == null || current.expires.isBefore(Instant.now())) {
            pending.remove(email);
            throw new IllegalArgumentException("Invalid code");
        }
        if (current.attempts >= MAX_ATTEMPTS) {
            pending.remove(email);
            throw new IllegalArgumentException("Too many attempts");
        }
        current.attempts++;
        if (code == null || !MessageDigest.isEqual(sha256(code.trim()), current.otpHash)) {
            throw new IllegalArgumentException("Invalid code");
        }
        pending.remove(email);
        RegistrationService.Result result = registrationService.registerCustomer(current.request);
        sendWelcomeMail(email, current.request.getCountry());
        return result;
    }

    private void issue(String email, RegisterRequest request, boolean resend) {
        Pending existing = pending.get(email);
        Instant now = Instant.now();
        if (resend && existing != null && existing.lastSent.plus(RESEND_COOLDOWN).isAfter(now)) {
            throw new IllegalArgumentException("Invalid request");
        }
        String code = String.format(Locale.ROOT, "%06d", RNG.nextInt(1_000_000));
        sendMail(email, code);
        pending.put(email, new Pending(request, sha256(code), now.plus(ttl), now, 0));
    }

    private void sendWelcomeMail(String to, String country) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || mailFrom.isBlank() || smtpPassword.isBlank() || welcomeHtml == null) {
            log.warn("Welcome mail skipped: SMTP or template not ready");
            return;
        }
        try {
            WelcomeEmailI18n i18n = WelcomeEmailI18n.fromCountry(country);
            String html = i18n.apply(welcomeHtml, frontendBaseUrl + "/login");
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

    private static String loadWelcomeHtml() {
        try (InputStream in = RegistrationOtpService.class.getResourceAsStream("/templates/welcome-email.html")) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private void sendMail(String to, String code) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || mailFrom.isBlank() || smtpPassword.isBlank()) {
            log.warn("SMTP not configured; registration OTP not sent");
            throw new IllegalStateException("Service unavailable");
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(mailFrom);
            msg.setTo(to);
            msg.setSubject("Your registration code");
            msg.setText("Your one-time code is " + code + "\nIt expires in " + ttl.toMinutes() + " minutes.");
            sender.send(msg);
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
                .vatNumber(src.getVatNumber())
                .country(src.getCountry())
                .referralCode(src.getReferralCode())
                .dpaAccepted(src.isDpaAccepted())
                .build();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Service unavailable");
        }
    }

    private static final class Pending {
        private final RegisterRequest request;
        private final byte[] otpHash;
        private final Instant expires;
        private final Instant lastSent;
        private int attempts;

        private Pending(RegisterRequest request, byte[] otpHash, Instant expires, Instant lastSent, int attempts) {
            this.request = request;
            this.otpHash = otpHash;
            this.expires = expires;
            this.lastSent = lastSent;
            this.attempts = attempts;
        }
    }
}
