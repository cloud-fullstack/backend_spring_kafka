package com.chatbot.auth.service.impl;

import com.chatbot.auth.dto.AuthResponse;
import com.chatbot.auth.dto.LoginRequest;
import com.chatbot.auth.dto.RegisterRequest;
import com.chatbot.auth.dto.ResellerRegisterRequest;
import com.chatbot.auth.dto.SocialLoginResponse;
import com.chatbot.auth.dto.SocialUser;
import com.chatbot.auth.model.User;
import com.chatbot.auth.model.Role;
import com.chatbot.auth.repository.UserRepository;
import com.chatbot.auth.security.JwtTokenProvider;
import com.chatbot.auth.service.SocialTokenValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthenticationManager authenticationManager;
    private final SocialTokenValidator socialTokenValidator;

    private static final Set<String> ALLOWED_PLANS = new HashSet<>(Arrays.asList(
        "trial", "bronze", "silver", "gold", "whatsapp-basic", "whatsapp-addon"
    ));

    private static final Set<String> BUSINESS_TYPES = new HashSet<>(Arrays.asList(
        "Pizza restaurants", "e-commerce", "B&B", "Labs", "doctors", "lawyers",
        "Agencies", "clinics", "networks", "barbers", "stores", "clinics"
    ));

    @Override
    @Transactional
    public AuthResponse registerUser(RegisterRequest request) {
        validatePlans(request.getPlans());
        validateBusinessType(request.getBusinessType());

        User user = new User();
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setEmail(request.getEmail());
        user.setCompanyName(request.getCompanyName());
        user.setBusinessType(request.getBusinessType());
        user.setPlans(new HashSet<>(Arrays.asList(request.getPlans())));
        user.setGoogleId(request.getGoogleId());
        user.setFacebookId(request.getFacebookId());

        if (request.getPassword() != null && request.getPassword().equals(request.getConfirmPassword())) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }

        user.setRole(Role.CUSTOMER);
        user.setActive(true);
        user = userRepository.save(user);

        String token = jwtTokenProvider.generateToken(user);
        return AuthResponse.builder()
                .token(token)
                .build();
    }

    @Override
    @Transactional
    public AuthResponse registerReseller(ResellerRegisterRequest request) {
        if (!request.isAgreedToTerms()) {
            throw new IllegalArgumentException("Terms and conditions must be accepted");
        }

        User user = new User();
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setCompanyName(request.getCompanyName());
        user.setWebsite(request.getWebsite());
        user.setBusinessType(request.getBusinessType());
        user.setServices(new HashSet<>(Arrays.asList(request.getServicesOffered())));
        user.setCustomizationNeeds(request.getCustomizationNeeds());
        user.setGoogleId(request.getGoogleId());
        user.setFacebookId(request.getFacebookId());

        if (request.getPassword() != null) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }

        user.setRole(Role.RESELLER);
        user.setActive(true);
        user = userRepository.save(user);

        String token = jwtTokenProvider.generateToken(user);
        return AuthResponse.builder()
                .token(token)
                .build();
    }

    @Override
    public AuthResponse authenticateUser(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getEmail(),
                        request.getPassword()
                )
        );

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        String token = jwtTokenProvider.generateToken(user);
        return AuthResponse.builder()
                .token(token)
                .build();
    }

    @Override
    public SocialLoginResponse socialLogin(String provider, String token) {
        SocialUser socialUser;
        String email;
        
        try {
            if (provider.equalsIgnoreCase("google")) {
                socialUser = socialTokenValidator.validateGoogleToken(token);
                email = socialUser.getEmail();
            } else if (provider.equalsIgnoreCase("facebook")) {
                socialUser = socialTokenValidator.validateFacebookToken(token);
                email = socialUser.getData().getUserId();
            } else {
                throw new IllegalArgumentException("Unsupported provider: " + provider);
            }
        } catch (Exception e) {
            throw new SecurityException("Failed to validate social token: " + e.getMessage());
        }

        User user = userRepository.findByEmail(email)
                .orElseGet(() -> createUserFromSocial(provider, email, socialUser));

        String jwtToken = jwtTokenProvider.generateToken(user);
        return SocialLoginResponse.builder()
                .success(true)
                .message("Login successful")
                .token(jwtToken)
                .user(UserDto.fromUser(user))
                .build();
    }

    @Override
    public boolean validateToken(String token) {
        return jwtTokenProvider.validateToken(token);
    }

    @Override
    public String getPlanFromToken(String token) {
        return jwtTokenProvider.getPlanFromToken(token);
    }

    @Override
    public boolean hasFeature(String token, String feature) {
        return jwtTokenProvider.hasFeature(token, feature);
    }

    @Override
    public boolean isSubscriptionValid(String token) {
        return jwtTokenProvider.isSubscriptionValid(token);
    }

    @Override
    public boolean isReseller(String token) {
        return jwtTokenProvider.isReseller(token);
    }

    @Override
    public boolean validatePlans(String[] plans) {
        if (plans == null || plans.length == 0) {
            throw new IllegalArgumentException("At least one plan must be selected");
        }
        
        for (String plan : plans) {
            if (!ALLOWED_PLANS.contains(plan.toLowerCase())) {
                throw new IllegalArgumentException("Invalid plan: " + plan);
            }
        }
        return true;
    }

    @Override
    public boolean canAddWhatsAppAddon(String plan) {
        return Arrays.asList("bronze", "silver", "gold").contains(plan.toLowerCase());
    }

    private User createUserFromSocial(String provider, String email, SocialUser socialUser) {
        User user = new User();
        user.setEmail(email);
        user.setFirstName(socialUser.getGivenName());
        user.setLastName(socialUser.getFamilyName());
        user.setRole(Role.CUSTOMER);
        user.setActive(true);
        user.setProfilePicture(socialUser.getPicture());
        
        if (provider.equalsIgnoreCase("google")) {
            user.setGoogleId(socialUser.getSub());
        } else if (provider.equalsIgnoreCase("facebook")) {
            user.setFacebookId(socialUser.getData().getUserId());
        }
        
        return userRepository.save(user);
    }

    private void validateBusinessType(String businessType) {
        if (!BUSINESS_TYPES.contains(businessType)) {
            throw new IllegalArgumentException("Invalid business type: " + businessType);
        }
    }
}
