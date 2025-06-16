package com.chatbot.auth.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResellerRegisterRequest {
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String companyName;
    private String website;
    private String businessType;
    private String referralCode;
    private String[] servicesOffered;
    private String customizationNeeds;
    private String additionalComments;
    private boolean agreedToTerms;
    private String googleId;
    private String facebookId;
    private String socialToken;
    private String socialProvider;
}
