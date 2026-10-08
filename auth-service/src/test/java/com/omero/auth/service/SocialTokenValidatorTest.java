package com.omero.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SocialTokenValidatorTest {

    @Test
    void fakeGoogleTokenIsRejected() {
        SocialTokenValidator validator = new SocialTokenValidator(
                new RestTemplate(), true, "test-google-client-id", false, "", "");
        assertThrows(SecurityException.class, () -> validator.validateGoogleToken("not-a-real-token"));
    }

    @Test
    void missingGoogleClientIdFailsStartupWhenEnabled() {
        assertThrows(IllegalStateException.class, () ->
                new SocialTokenValidator(new RestTemplate(), true, " ", false, "", ""));
    }

    @Test
    void facebookTokenFromAnotherAppIsRejected() {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.getForObject(anyString(), eq(Map.class))).thenReturn(Map.of(
                "data", Map.of("is_valid", true, "app_id", "other-app")));
        SocialTokenValidator validator = new SocialTokenValidator(
                rest, false, "", true, "our-app-id", "our-secret");
        assertThrows(SecurityException.class, () -> validator.validateFacebookToken("stolen-token"));
    }

    @Test
    void facebookHttpErrorBecomesSecurityException() {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.getForObject(anyString(), eq(Map.class)))
                .thenThrow(HttpClientErrorException.Unauthorized.create(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "gone",
                        org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));
        SocialTokenValidator validator = new SocialTokenValidator(
                rest, false, "", true, "our-app-id", "our-secret");
        assertThrows(SecurityException.class, () -> validator.validateFacebookToken("expired"));
    }

    @Test
    void facebookTokenForThisAppLoadsProfile() {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.getForObject(anyString(), eq(Map.class))).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            if (url.contains("debug_token")) {
                return Map.of("data", Map.of("is_valid", true, "app_id", "our-app-id"));
            }
            return Map.of("id", "fb1", "email", "a@example.com", "first_name", "Ann");
        });
        SocialTokenValidator validator = new SocialTokenValidator(
                rest, false, "", true, "our-app-id", "our-secret");
        var user = validator.validateFacebookToken("ok-token");
        assertEquals("a@example.com", user.getEmail());
        assertTrue(user.isEmailVerified());
        assertEquals("fb1", user.getSub());
    }
}
