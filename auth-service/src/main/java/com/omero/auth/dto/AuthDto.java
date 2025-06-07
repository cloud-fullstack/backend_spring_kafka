package com.omero.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuthResponse {
    private boolean success;
    private String message;
    private String token;
    private String referralCode;
}

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RegisterRequest {
    private String firstName;
    private String lastName;
    private String email;
    private String password;
    private String confirmPassword;
    private String planName;
    private String businessType;
    private String companyName;
    private String referralCode;
}

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ResellerRegisterRequest {
    private String firstName;
    private String lastName;
    private String email;
    private String password;
    private String company;
    private String phone;
    private String website;
    private String experience;
    private String marketingPlan;
}

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LoginRequest {
    private String email;
    private String password;
}

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserDto {
    private String id;
    private String email;
    private String password;
    private String firstName;
    private String lastName;
    private String role;
    private boolean isActive;
    private boolean isReseller;
    private String planName;
    private LocalDateTime subscriptionStartDate;
    private LocalDateTime subscriptionEndDate;
    private Set<String> authorities;
}
