package com.omero.auth.service;

import com.omero.auth.dto.RegisterRequest;
import com.omero.auth.dto.ResellerRegisterRequest;
import com.omero.auth.dto.SocialUser;
import com.omero.auth.entity.Reseller;
import com.omero.auth.entity.Tenant;
import com.omero.auth.entity.User;
import com.omero.auth.repository.BusinessVerticalRepository;
import com.omero.auth.repository.ResellerRepository;
import com.omero.auth.repository.TenantRepository;
import com.omero.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);
    private static final Pattern REFERRAL = Pattern.compile("^PARTNER[A-Z0-9]{4,6}$");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
    private static final String ALPHANUM = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RNG = new SecureRandom();

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final ResellerRepository resellerRepository;
    private final BusinessVerticalRepository verticalRepository;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(
            UserRepository userRepository,
            TenantRepository tenantRepository,
            ResellerRepository resellerRepository,
            BusinessVerticalRepository verticalRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.resellerRepository = resellerRepository;
        this.verticalRepository = verticalRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public record Result(User user, String referralCode) {}

    @Transactional
    public Result registerCustomer(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());
        if (request.getPassword() == null || !request.getPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("Passwords do not match");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("Email already registered");
        }
        String vertical = Optional.ofNullable(request.getVertical()).orElse("").trim().toLowerCase(Locale.ROOT);
        if (vertical.isBlank() || !verticalRepository.existsByCodeAndActiveTrue(vertical)) {
            throw new IllegalArgumentException("Invalid vertical");
        }
        String country = Optional.ofNullable(request.getCountry()).orElse("").trim().toUpperCase(Locale.ROOT);
        if (!country.isBlank() && !COUNTRY.matcher(country).matches()) {
            throw new IllegalArgumentException("Invalid country");
        }

        Tenant tenant = newDemoTenant(email, request.getCompanyName(), vertical, country);
        tenant.setCompanyName(request.getCompanyName());
        tenant.setVatNumber(request.getVatNumber());
        attributeReseller(tenant, request.getReferralCode());
        tenant = tenantRepository.save(tenant);

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPlanId("demo");
        user.setTenantId(tenant.getId());
        user.setBusinessName(request.getCompanyName());
        user.setActive(true);
        user.setReseller(false);
        user.setTenantAdmin(true);
        user = userRepository.save(user);
        return new Result(user, null);
    }

    @Transactional
    public Result registerReseller(ResellerRegisterRequest request) {
        if (!request.isAgreedToTerms()) {
            throw new IllegalArgumentException("Terms and conditions must be accepted");
        }
        if (request.getPassword() == null || !request.getPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("Passwords do not match");
        }
        String email = normalizeEmail(request.getEmail());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("Email already registered");
        }
        String country = Optional.ofNullable(request.getCountry()).orElse("").trim().toUpperCase(Locale.ROOT);
        if (!country.isBlank() && !COUNTRY.matcher(country).matches()) {
            throw new IllegalArgumentException("Invalid country");
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPhoneNumber(request.getPhone());
        user.setPlanId("demo");
        user.setBusinessName(request.getCompanyName());
        user.setActive(false);
        user.setReseller(true);
        user.setTenantAdmin(false);
        user = userRepository.save(user);

        Reseller reseller = new Reseller();
        reseller.setUserId(user.getId());
        reseller.setCompanyName(Optional.ofNullable(request.getCompanyName()).orElse(""));
        reseller.setVatNumber(request.getVatNumber());
        reseller.setCountry(country.isBlank() ? null : country);
        reseller.setStatus("pending");
        reseller.setReferralCode(newReferralCode());
        resellerRepository.save(reseller);
        return new Result(user, reseller.getReferralCode());
    }

    @Transactional
    public User createFromSocial(String provider, SocialUser socialUser) {
        if (socialUser == null || !socialUser.isEmailVerified()) {
            throw new SecurityException("Invalid credentials");
        }
        String email = normalizeEmail(socialUser.getEmail());
        Tenant tenant = newDemoTenant(email, email, null, null);
        tenant = tenantRepository.save(tenant);

        User user = new User();
        user.setEmail(email);
        user.setFirstName(socialUser.getGivenName());
        user.setLastName(socialUser.getFamilyName());
        user.setPlanId("demo");
        user.setTenantId(tenant.getId());
        user.setActive(true);
        user.setReseller(false);
        user.setTenantAdmin(true);
        if ("google".equalsIgnoreCase(provider)) {
            user.setGoogleId(socialUser.getSub());
        } else {
            user.setFacebookId(socialUser.getSub());
        }
        return userRepository.save(user);
    }

    @Transactional
    public void completeProfile(java.util.UUID userId, String verticalRaw, String companyName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new SecurityException("Invalid credentials"));
        if (user.getTenantId() == null) {
            throw new IllegalArgumentException("Invalid request");
        }
        String vertical = Optional.ofNullable(verticalRaw).orElse("").trim().toLowerCase(Locale.ROOT);
        if (vertical.isBlank() || !verticalRepository.existsByCodeAndActiveTrue(vertical)) {
            throw new IllegalArgumentException("Invalid vertical");
        }
        Tenant tenant = tenantRepository.findById(user.getTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Invalid request"));
        tenant.setVertical(vertical);
        if (companyName != null && !companyName.isBlank()) {
            String name = companyName.trim();
            tenant.setCompanyName(name);
            tenant.setName(name);
            user.setBusinessName(name);
            userRepository.save(user);
        }
        tenantRepository.save(tenant);
    }

    private Tenant newDemoTenant(String email, String name, String vertical, String country) {
        Tenant tenant = new Tenant();
        tenant.setName(Optional.ofNullable(name).filter(s -> !s.isBlank()).orElse(email));
        tenant.setEmail(email);
        tenant.setStatus("demo");
        tenant.setVertical(vertical);
        tenant.setCountry(country);
        tenant.setLastSeenAt(OffsetDateTime.now());
        return tenant;
    }

    private void attributeReseller(Tenant tenant, String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return;
        }
        String code = rawCode.trim().toUpperCase(Locale.ROOT);
        if (!REFERRAL.matcher(code).matches()) {
            log.info("Ignoring invalid referral format during registration");
            return;
        }
        resellerRepository.findByReferralCodeAndStatus(code, "active").ifPresentOrElse(reseller -> {
            if (tenant.getResellerId() == null) {
                tenant.setResellerId(reseller.getId());
                tenant.setResellerAttributedAt(OffsetDateTime.now());
            }
        }, () -> log.info("Referral code not active, continuing without reseller: {}", code));
    }

    String newReferralCode() {
        for (int i = 0; i < 20; i++) {
            String code = "PARTNER" + randomCode(6);
            if (!resellerRepository.existsByReferralCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not allocate referral code");
    }

    static String normalizeEmail(String email) {
        if (email == null) {
            throw new IllegalArgumentException("Invalid request");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String randomCode(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHANUM.charAt(RNG.nextInt(ALPHANUM.length())));
        }
        return sb.toString();
    }
}
