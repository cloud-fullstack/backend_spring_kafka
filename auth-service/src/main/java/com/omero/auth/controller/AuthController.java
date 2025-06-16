package com.omero.auth.controller;

import com.omero.auth.dto.*;
import com.omero.auth.service.AuthService;
import com.omero.auth.service.TokenService;
import com.omero.auth.service.UserService;
import com.chatbot.auth.dto.SocialUser;
import com.chatbot.auth.service.SocialTokenValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final AuthService authService;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final SocialTokenValidator socialTokenValidator;

    // Plan configurations
    private static final Map<String, PlanConfig> PLAN_CONFIGS = new HashMap<>();
    static {
        PLAN_CONFIGS.put("free", new PlanConfig(4, 500, 1, false, false, false));
        PLAN_CONFIGS.put("bronze", new PlanConfig(30, 10000, 0, true, false, false));
        PLAN_CONFIGS.put("silver", new PlanConfig(30, 15000, 10, true, false, false));
        PLAN_CONFIGS.put("gold", new PlanConfig(30, -1, -1, true, true, true));
        PLAN_CONFIGS.put("whatsapp_jolly_basic", new PlanConfig(30, 5000, 0, false, false, false));
        PLAN_CONFIGS.put("whatsapp_jolly_addon", new PlanConfig(30, -1, -1, false, false, false));
    }

    private record PlanConfig(int durationDays, int messageLimit, int voiceHours, boolean webChat, boolean voip, boolean whatsapp) {}

    private final UserService userService;
    private final AuthService authService;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody @Validated RegisterRequest request) {
        // Validate password match
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            return ResponseEntity.badRequest().body(new AuthResponse(false, "Passwords do not match"));
        }

        // Create user
        UserDto user = UserDto.builder()
                .id(UUID.randomUUID().toString())
                .email(request.getEmail())
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .password(passwordEncoder.encode(request.getPassword()))
                .role("USER")
                .isActive(true)
                .isReseller(false)
                .planName(request.getPlanName())
                .subscriptionStartDate(LocalDateTime.now())
                .subscriptionEndDate(calculateEndDate(request.getPlanName(), LocalDateTime.now()))
                .authorities(Collections.singleton("ROLE_USER"))
                .build();

        // Save user
        UserDto savedUser = userService.registerUser(user);

        // Generate JWT token
        String token = tokenService.generateToken(savedUser, request.getPlanName());

        return ResponseEntity.ok(new AuthResponse(true, "Registration successful", token));
    }

    @PostMapping("/reseller/register")
    public ResponseEntity<AuthResponse> registerReseller(@RequestBody @Validated ResellerRegisterRequest request) {
        // Generate referral code
        String referralCode = "PARTNER" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Create reseller user
        UserDto user = UserDto.builder()
                .id(UUID.randomUUID().toString())
                .email(request.getEmail())
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .password(passwordEncoder.encode(request.getPassword()))
                .role("RESELLER")
                .isActive(true)
                .isReseller(true)
                .planName("bronze") // Default reseller plan
                .subscriptionStartDate(LocalDateTime.now())
                .subscriptionEndDate(calculateEndDate("bronze", LocalDateTime.now()))
                .authorities(Collections.singleton("ROLE_RESELLER"))
                .build();

        // Save user
        UserDto savedUser = userService.registerUser(user);

        // Generate JWT token
        String token = tokenService.generateToken(savedUser, "bronze");

        return ResponseEntity.ok(new AuthResponse(true, "Reseller registration successful", token, referralCode));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody @Validated LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);

        String token = tokenService.generateToken(
                (UserDetails) authentication.getPrincipal(),
                authentication.getAuthorities().toString()
        );

        return ResponseEntity.ok(new AuthResponse(true, "Login successful", token));
    }

    private LocalDateTime calculateEndDate(String planName, LocalDateTime startDate) {
        PlanConfig config = PLAN_CONFIGS.getOrDefault(planName.toLowerCase(), PLAN_CONFIGS.get("free"));
        return startDate.plusDays(config.durationDays());
    }

    private UserDto buildUserWithPlan(String planName, String email, String firstName, String lastName, String password) {
        PlanConfig config = PLAN_CONFIGS.getOrDefault(planName.toLowerCase(), PLAN_CONFIGS.get("free"));
        
        return UserDto.builder()
                .id(UUID.randomUUID().toString())
                .email(email)
                .firstName(firstName)
                .lastName(lastName)
                .password(passwordEncoder.encode(password))
                .role("USER")
                .isActive(true)
                .isReseller(false)
                .planName(planName)
                .subscriptionStartDate(LocalDateTime.now())
                .subscriptionEndDate(calculateEndDate(planName, LocalDateTime.now()))
                .messageLimit(config.messageLimit())
                .voiceHoursLimit(config.voiceHours())
                .webChatEnabled(config.webChat())
                .voipEnabled(config.voip())
                .whatsappEnabled(config.whatsapp())
                .authorities(Collections.singleton("ROLE_USER"))
                .build();
    }
}
