package com.omero.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omero.auth.service.LemonWebhookService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LemonWebhookControllerTest {

    @Mock LemonWebhookService service;

    @Test
    void blankSecretIs503AndDoesNotHandle() throws Exception {
        LemonWebhookController c = new LemonWebhookController(service, new ObjectMapper(), "  ");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, c.webhook("{}".getBytes(), "sig").getStatusCode());
        verify(service, never()).handle(any());
    }
}
