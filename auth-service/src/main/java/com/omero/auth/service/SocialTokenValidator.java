package com.omero.auth.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.omero.auth.dto.SocialUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class SocialTokenValidator {

    private final RestTemplate restTemplate;
    private final boolean googleEnabled;
    private final String googleClientId;
    private final GoogleIdTokenVerifier googleVerifier;
    private final boolean facebookEnabled;
    private final String facebookAppId;
    private final String facebookAppSecret;

    public SocialTokenValidator(
            RestTemplate restTemplate,
            @Value("${social.google.enabled:false}") boolean googleEnabled,
            @Value("${social.google.client-id:}") String googleClientId,
            @Value("${social.facebook.enabled:false}") boolean facebookEnabled,
            @Value("${social.facebook.client-id:}") String facebookAppId,
            @Value("${social.facebook.client-secret:}") String facebookAppSecret) {
        this.restTemplate = restTemplate;
        this.googleClientId = googleClientId == null ? "" : googleClientId.trim();
        boolean googleOn = googleEnabled && !this.googleClientId.isBlank();
        this.googleEnabled = googleOn;
        if (googleOn) {
            this.googleVerifier = new GoogleIdTokenVerifier.Builder(
                    new NetHttpTransport(), GsonFactory.getDefaultInstance())
                    .setAudience(Collections.singletonList(this.googleClientId))
                    .setIssuers(List.of("accounts.google.com", "https://accounts.google.com"))
                    .build();
        } else {
            this.googleVerifier = null;
        }
        this.facebookAppId = facebookAppId == null ? "" : facebookAppId.trim();
        this.facebookAppSecret = facebookAppSecret == null ? "" : facebookAppSecret;
        this.facebookEnabled = facebookEnabled && !this.facebookAppId.isBlank() && !this.facebookAppSecret.isBlank();
    }

    public boolean isGoogleEnabled() {
        return googleEnabled;
    }

    public String getGoogleClientId() {
        return googleClientId;
    }

    public boolean isFacebookEnabled() {
        return facebookEnabled;
    }

    public String getFacebookAppId() {
        return facebookAppId;
    }

    public SocialUser validate(String provider, String idToken) {
        if (idToken == null || idToken.isBlank()) {
            throw new SecurityException("Missing token");
        }
        if ("google".equalsIgnoreCase(provider)) {
            return validateGoogleToken(idToken);
        }
        if ("facebook".equalsIgnoreCase(provider)) {
            return validateFacebookToken(idToken);
        }
        throw new IllegalArgumentException("Invalid request");
    }

    public SocialUser validateGoogleToken(String idToken) {
        if (!googleEnabled || googleVerifier == null) {
            throw new SecurityException("Invalid credentials");
        }
        try {
            GoogleIdToken parsed = googleVerifier.verify(idToken);
            if (parsed == null) {
                throw new SecurityException("Invalid Google token");
            }
            GoogleIdToken.Payload payload = parsed.getPayload();
            boolean verified = Boolean.TRUE.equals(payload.getEmailVerified());
            if (!verified) {
                throw new SecurityException("Google email not verified");
            }
            if (payload.getEmail() == null || payload.getEmail().isBlank()) {
                throw new SecurityException("Google email missing");
            }
            SocialUser user = new SocialUser();
            user.setIss(payload.getIssuer());
            user.setSub(payload.getSubject());
            user.setAud(String.valueOf(payload.getAudience()));
            user.setEmail(payload.getEmail());
            user.setEmailVerified(true);
            user.setName((String) payload.get("name"));
            user.setPicture((String) payload.get("picture"));
            user.setGivenName((String) payload.get("given_name"));
            user.setFamilyName((String) payload.get("family_name"));
            return user;
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("Invalid Google token");
        }
    }

    public SocialUser validateFacebookToken(String accessToken) {
        if (!facebookEnabled) {
            throw new SecurityException("Invalid credentials");
        }
        try {
            String inspectedUserId = inspectFacebookToken(accessToken);
            String proof = appSecretProof(accessToken);
            String url = UriComponentsBuilder.fromUriString("https://graph.facebook.com/me")
                    .queryParam("fields", "id,email,first_name,last_name")
                    .queryParam("appsecret_proof", proof)
                    .build()
                    .encode()
                    .toUriString();
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<String, Object> body = response.getBody();
            if (body == null || body.get("email") == null || String.valueOf(body.get("email")).isBlank()) {
                throw new SecurityException("Facebook email missing");
            }
            String meId = String.valueOf(body.get("id"));
            if (!inspectedUserId.equals(meId)) {
                throw new SecurityException("Invalid Facebook token");
            }
            SocialUser user = new SocialUser();
            user.setSub(meId);
            user.setEmail(String.valueOf(body.get("email")));
            // Facebook has no reliable email_verified flag. New accounts may still be
            // created; linking to an existing user is refused in AuthController.linkExisting.
            user.setEmailVerified(true);
            user.setGivenName(body.get("first_name") != null ? String.valueOf(body.get("first_name")) : null);
            user.setFamilyName(body.get("last_name") != null ? String.valueOf(body.get("last_name")) : null);
            return user;
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("Invalid Facebook token");
        }
    }

    /**
     * POST so app_id|app_secret is not in the query string (proxies / RestTemplate DEBUG logs).
     */
    @SuppressWarnings("unchecked")
    private String inspectFacebookToken(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("input_token", accessToken);
        form.add("access_token", facebookAppId + "|" + facebookAppSecret);
        Map<String, Object> envelope = restTemplate.postForObject(
                "https://graph.facebook.com/debug_token",
                new HttpEntity<>(form, headers),
                Map.class);
        Map<String, Object> data = envelope == null ? null : (Map<String, Object>) envelope.get("data");
        if (data == null
                || !Boolean.TRUE.equals(data.get("is_valid"))
                || !facebookAppId.equals(String.valueOf(data.get("app_id")))
                || data.get("user_id") == null) {
            throw new SecurityException("Invalid Facebook token");
        }
        return String.valueOf(data.get("user_id"));
    }

    private String appSecretProof(String accessToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(facebookAppSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(accessToken.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new SecurityException("Invalid Facebook token");
        }
    }
}
