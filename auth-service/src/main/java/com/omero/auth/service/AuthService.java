package com.chatbot.auth.service;

import com.chatbot.auth.dto.AuthResponse;
import com.chatbot.auth.dto.LoginRequest;
import com.chatbot.auth.dto.RegisterRequest;
import com.chatbot.auth.dto.ResellerRegisterRequest;
import com.chatbot.auth.dto.SocialLoginResponse;

public interface AuthService {
    AuthResponse registerUser(RegisterRequest request);
    AuthResponse registerReseller(ResellerRegisterRequest request);
    AuthResponse authenticateUser(LoginRequest request);
    SocialLoginResponse socialLogin(String provider, String token);
    boolean validateToken(String token);
    String getPlanFromToken(String token);
    boolean hasFeature(String token, String feature);
    boolean isSubscriptionValid(String token);
    boolean isReseller(String token);
    boolean validatePlans(String[] plans);
    boolean canAddWhatsAppAddon(String plan);
}
