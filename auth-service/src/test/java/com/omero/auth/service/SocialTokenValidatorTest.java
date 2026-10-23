package com.omero.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SocialTokenValidatorTest {

    private static SocialTokenValidator fb(RestTemplate rest) {
        return new SocialTokenValidator(
                rest, false, "", true, "our-app-id", "our-secret");
    }

    private static void debugToken(RestTemplate rest, String appId) {
        when(rest.postForObject(contains("debug_token"), any(), eq(Map.class)))
                .thenReturn(Map.of("data", Map.of(
                        "is_valid", true,
                        "app_id", appId,
                        "user_id", "fb1")));
    }

    private static void me(RestTemplate rest, String id) {
        doReturn(ResponseEntity.ok(Map.of(
                "id", id, "email", "a@example.com",
                "first_name", "Ann")))
                .when(rest).exchange(contains("graph.facebook.com/me"),
                        eq(HttpMethod.GET), any(HttpEntity.class),
                        eq(Map.class));
    }

    @Test
    void fakeGoogleTokenIsRejected() {
        SocialTokenValidator validator = new SocialTokenValidator(
                new RestTemplate(), true, "test-google-client-id", false, "", "");
        assertThrows(SecurityException.class, () -> validator.validateGoogleToken("not-a-real-token"));
    }

    @Test
    void blankGoogleClientIdDisablesGoogleInsteadOfCrashing() {
        SocialTokenValidator validator = new SocialTokenValidator(
                new RestTemplate(), true, " ", false, "", "");
        assertFalse(validator.isGoogleEnabled());
        assertFalse(validator.isFacebookEnabled());
        assertThrows(SecurityException.class, () -> validator.validateGoogleToken("not-a-real-token"));
    }

    @Test
    void facebookTokenFromAnotherAppIsRejected() {
        RestTemplate rest = mock(RestTemplate.class);
        debugToken(rest, "other-app");
        assertThrows(SecurityException.class,
                () -> fb(rest).validateFacebookToken("stolen"));
        verify(rest, never()).exchange(anyString(),
                any(HttpMethod.class), any(HttpEntity.class),
                eq(Map.class));
    }

    @Test
    void facebookHttpErrorBecomesSecurityException() {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.postForObject(anyString(), any(), eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNAUTHORIZED, "gone",
                        HttpHeaders.EMPTY, new byte[0], null));
        assertThrows(SecurityException.class,
                () -> fb(rest).validateFacebookToken("expired"));
    }

    @Test
    void facebookTokenForThisAppLoadsProfile() {
        RestTemplate rest = mock(RestTemplate.class);
        debugToken(rest, "our-app-id");
        me(rest, "fb1");
        var user = fb(rest).validateFacebookToken("ok");
        assertEquals("a@example.com", user.getEmail());
        assertTrue(user.isEmailVerified());
        assertEquals("fb1", user.getSub());
    }

    @Test
    void facebookProfileOfAnotherUserIsRejected() {
        RestTemplate rest = mock(RestTemplate.class);
        debugToken(rest, "our-app-id");
        me(rest, "fb2");
        assertThrows(SecurityException.class,
                () -> fb(rest).validateFacebookToken("ok"));
    }
}
