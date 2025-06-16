package com.chatbot.auth.service;

import com.chatbot.auth.dto.SocialUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class SocialTokenValidator {

    @Value("${social.google.client-id}")
    private String googleClientId;

    @Value("${social.facebook.client-id}")
    private String facebookClientId;

    @Value("${social.facebook.app-secret}")
    private String facebookAppSecret;

    private static final String GOOGLE_TOKEN_INFO_URL = "https://oauth2.googleapis.com/tokeninfo";
    private static final String FACEBOOK_TOKEN_INFO_URL = "https://graph.facebook.com/debug_token";

    public SocialUser validateGoogleToken(String idToken) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/x-www-form-urlencoded");

        Map<String, String> params = new HashMap<>();
        params.put("id_token", idToken);

        ResponseEntity<SocialUser> response = restTemplate.exchange(
                GOOGLE_TOKEN_INFO_URL,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                SocialUser.class,
                params
        );

        SocialUser user = response.getBody();
        if (user == null || !user.getAud().equals(googleClientId)) {
            throw new SecurityException("Invalid Google token");
        }

        return user;
    }

    public SocialUser validateFacebookToken(String accessToken) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/x-www-form-urlencoded");

        Map<String, String> params = new HashMap<>();
        params.put("input_token", accessToken);
        params.put("access_token", facebookClientId + "|" + facebookAppSecret);

        ResponseEntity<SocialUser> response = restTemplate.exchange(
                FACEBOOK_TOKEN_INFO_URL,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                SocialUser.class,
                params
        );

        SocialUser user = response.getBody();
        if (user == null || !user.getData().getIsValid()) {
            throw new SecurityException("Invalid Facebook token");
        }

        return user;
    }
}
