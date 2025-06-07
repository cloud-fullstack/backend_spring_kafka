package com.omero.auth.service;

import com.omero.auth.dto.AuthResponse;
import com.omero.auth.dto.LoginRequest;
import com.omero.auth.dto.RegisterRequest;
import com.omero.auth.dto.ResellerRegisterRequest;

public interface AuthService {
    AuthResponse registerUser(RegisterRequest request);
    AuthResponse registerReseller(ResellerRegisterRequest request);
    AuthResponse authenticateUser(LoginRequest request);
    boolean validateToken(String token);
    String getPlanFromToken(String token);
    boolean hasFeature(String token, String feature);
    boolean isSubscriptionValid(String token);
    boolean isReseller(String token);
}
