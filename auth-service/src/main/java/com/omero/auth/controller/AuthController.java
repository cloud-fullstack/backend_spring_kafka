package com.omero.auth.controller;

import com.omero.auth.dto.AuthResponse;
import com.omero.auth.dto.CompleteProfileRequest;
import com.omero.auth.dto.LoginRequest;
import com.omero.auth.dto.RegisterRequest;
import com.omero.auth.dto.ResellerRegisterRequest;
import com.omero.auth.dto.SocialLoginResponse;
import com.omero.auth.dto.SocialUser;
import com.omero.auth.entity.Tenant;
import com.omero.auth.entity.User;
import com.omero.auth.repository.TenantRepository;
import com.omero.auth.repository.UserRepository;
import com.omero.auth.security.JwtTokenProvider;
import com.omero.auth.service.PlanAccessService;
import com.omero.auth.service.RegistrationService;
import com.omero.auth.service.SocialTokenValidator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final RegistrationService registrationService;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final PlanAccessService planAccessService;
    private final SocialTokenValidator socialTokenValidator;
    private final com.omero.auth.service.PasswordResetService passwordResetService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody @Valid RegisterRequest request) {
        RegistrationService.Result result = registrationService.registerCustomer(request);
        String token = jwtTokenProvider.generateToken(result.user());
        return ResponseEntity.ok(AuthResponse.builder()
                .success(true)
                .message("Registration successful")
                .token(token)
                .build());
    }

    @PostMapping("/reseller/register")
    public ResponseEntity<AuthResponse> registerReseller(@RequestBody @Valid ResellerRegisterRequest request) {
        RegistrationService.Result result = registrationService.registerReseller(request);
        return ResponseEntity.ok(AuthResponse.builder()
                .success(true)
                .code("RESELLER_PENDING")
                .token(null)
                .referralCode(result.referralCode())
                .build());
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, Object>> forgotPassword(@RequestBody Map<String, String> body) {
        passwordResetService.forgot(body == null ? null : body.get("email"));
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(@RequestBody Map<String, String> body) {
        String token = body == null ? null : body.get("token");
        String password = body == null ? null : body.get("password");
        passwordResetService.reset(token, password);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody @Valid LoginRequest request) {
        User user = userRepository.findByEmailIgnoreCase(RegistrationService.normalizeEmail(request.getEmail()))
                .orElseThrow(() -> new SecurityException("Invalid credentials"));
        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new SecurityException("Invalid credentials");
        }
        if (!user.isActive()) {
            throw new SecurityException("Invalid credentials");
        }
        String token = jwtTokenProvider.generateToken(user);
        return ResponseEntity.ok(AuthResponse.builder()
                .success(true)
                .message("Login successful")
                .token(token)
                .build());
    }

    @GetMapping("/social/config")
    public Map<String, Object> socialConfig() {
        return Map.of(
                "googleEnabled", socialTokenValidator.isGoogleEnabled(),
                "googleClientId", socialTokenValidator.getGoogleClientId() == null
                        ? "" : socialTokenValidator.getGoogleClientId(),
                "facebookEnabled", socialTokenValidator.isFacebookEnabled(),
                "facebookAppId", socialTokenValidator.getFacebookAppId() == null
                        ? "" : socialTokenValidator.getFacebookAppId()
        );
    }

    @PostMapping("/social/{provider}")
    public ResponseEntity<SocialLoginResponse> socialLogin(
            @PathVariable String provider,
            @RequestBody Map<String, String> body) {
        if (!"google".equalsIgnoreCase(provider) && !"facebook".equalsIgnoreCase(provider)) {
            throw new IllegalArgumentException("Invalid request");
        }
        String idToken = body == null ? null : body.get("id_token");
        SocialUser socialUser = socialTokenValidator.validate(provider, idToken);
        if (!socialUser.isEmailVerified()) {
            throw new SecurityException("Invalid credentials");
        }
        User user = userRepository.findByEmailIgnoreCase(RegistrationService.normalizeEmail(socialUser.getEmail()))
                .map(existing -> linkExisting(provider, existing, socialUser))
                .orElseGet(() -> registrationService.createFromSocial(provider, socialUser));
        if (!user.isActive()) {
            throw new SecurityException("Invalid credentials");
        }
        String jwt = jwtTokenProvider.generateToken(user);
        boolean incomplete = tenantVerticalMissing(user);
        return ResponseEntity.ok(SocialLoginResponse.builder()
                .success(true)
                .message("Login successful")
                .token(jwt)
                .profileIncomplete(incomplete)
                .build());
    }

    @PostMapping("/complete-profile")
    public ResponseEntity<Map<String, Object>> completeProfile(@RequestBody @Valid CompleteProfileRequest request) {
        registrationService.completeProfile(currentUserId(), request.getVertical(), request.getCompanyName());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/plan")
    public ResponseEntity<Map<String, Object>> currentPlan() {
        UUID uid = currentUserId();
        PlanAccessService.Entitlement e = planAccessService.resolveByUserId(uid);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("planId", e.planId() == null ? "" : e.planId());
        body.put("tenantStatus", e.tenantStatus() == null ? "" : e.tenantStatus());
        return ResponseEntity.ok(body);
    }

    private UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UUID uid)) {
            throw new SecurityException("Invalid credentials");
        }
        return uid;
    }

    private User linkExisting(String provider, User existing, SocialUser socialUser) {
        String sub = socialUser.getSub();
        if ("google".equalsIgnoreCase(provider)) {
            if (existing.getGoogleId() != null && !existing.getGoogleId().equals(sub)) {
                throw new SecurityException("Invalid credentials");
            }
            if (existing.getGoogleId() == null) {
                existing.setGoogleId(sub);
            }
        } else if ("facebook".equalsIgnoreCase(provider)) {
            if (existing.getFacebookId() == null || !existing.getFacebookId().equals(sub)) {
                throw new SecurityException("Invalid credentials");
            }
        }
        return userRepository.save(existing);
    }

    private boolean tenantVerticalMissing(User user) {
        if (user.getTenantId() == null) {
            return true;
        }
        return tenantRepository.findById(user.getTenantId())
                .map(Tenant::getVertical)
                .map(v -> v == null || v.isBlank())
                .orElse(true);
    }
}
