package com.chatbot.auth.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SocialUser {
    private String iss;
    private String sub;
    private String aud;
    private String email;
    private boolean emailVerified;
    private String name;
    private String picture;
    private String givenName;
    private String familyName;
    private String locale;
    private Data data;  // For Facebook

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Data {
        private boolean isValid;
        private String userId;
        private String appId;
        private String application;
        private String type;
        private String expiresAt;
        private String issuedAt;
        private String scopes;
    }
}
