package com.omero.auth.service;

import com.omero.auth.config.TurnstileProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TurnstileServiceTest {

    @Mock RestTemplate restTemplate;

    private TurnstileProperties props;
    private TurnstileService service;

    @BeforeEach
    void setUp() {
        props = new TurnstileProperties();
        service = new TurnstileService(props, restTemplate);
    }

    @Test
    void disabledPassesWithoutCall() {
        props.setEnabled(false);
        props.setSecret("secret");
        assertTrue(service.verify(""));
        verify(restTemplate, never()).postForObject(anyString(), any(), eq(Map.class));
    }

    @Test
    void blankSecretPassesWithoutCall() {
        props.setEnabled(true);
        props.setSecret("  ");
        assertTrue(service.verify(null));
        verify(restTemplate, never()).postForObject(anyString(), any(), eq(Map.class));
    }

    @Test
    void enabledBlankTokenFails() {
        props.setEnabled(true);
        props.setSecret("cf-secret");
        assertFalse(service.verify(" "));
        verify(restTemplate, never()).postForObject(anyString(), any(), eq(Map.class));
    }

    @Test
    void siteverifySuccess() {
        props.setEnabled(true);
        props.setSecret("cf-secret");
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(Map.of("success", true));
        assertTrue(service.verify("tok"));
    }

    @Test
    void siteverifyFailure() {
        props.setEnabled(true);
        props.setSecret("cf-secret");
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(Map.of("success", false, "error-codes", "invalid-input-response"));
        assertFalse(service.verify("tok"));
    }

    @Test
    void siteverifyErrorFailsClosed() {
        props.setEnabled(true);
        props.setSecret("cf-secret");
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("timeout"));
        assertFalse(service.verify("tok"));
    }
}
