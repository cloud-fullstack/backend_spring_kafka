package com.chatbot.auth.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {
    private String firstName;
    private String lastName;
    private String email;
    private String password;
    private String confirmPassword;
    private String[] plans;  // Array of selected plans
    private String businessType;
    private String companyName;
    private String referralCode;
    private String googleId;
    private String facebookId;
    private String socialToken;
    private String socialProvider;  // "google" or "facebook"
}
