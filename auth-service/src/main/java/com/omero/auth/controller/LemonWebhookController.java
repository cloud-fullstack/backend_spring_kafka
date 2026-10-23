package com.omero.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omero.auth.security.LemonSignature;
import com.omero.auth.service.LemonWebhookService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LemonWebhookController {

    private final LemonWebhookService webhookService;
    private final ObjectMapper objectMapper;
    private final String webhookSecret;

    public LemonWebhookController(
            LemonWebhookService webhookService,
            ObjectMapper objectMapper,
            @Value("${lemonsqueezy.webhook-secret:}") String webhookSecret) {
        this.webhookService = webhookService;
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping("/webhooks/lemonsqueezy")
    public ResponseEntity<Void> webhook(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Signature", required = false) String sig) throws Exception {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            return ResponseEntity.status(503).build();
        }
        if (!LemonSignature.isValid(webhookSecret, body, sig)) {
            return ResponseEntity.status(401).build();
        }
        webhookService.handle(objectMapper.readTree(body));
        return ResponseEntity.ok().build();
    }
}
