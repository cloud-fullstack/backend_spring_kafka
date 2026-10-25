package com.omero.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omero.auth.security.StripeSignature;
import com.omero.auth.service.StripeWebhookService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
public class StripeWebhookController {

    private final StripeWebhookService webhookService;
    private final ObjectMapper objectMapper;
    private final String webhookSecret;

    public StripeWebhookController(
            StripeWebhookService webhookService,
            ObjectMapper objectMapper,
            @Value("${stripe.webhook-secret:}") String webhookSecret) {
        this.webhookService = webhookService;
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping("/webhooks/stripe")
    public ResponseEntity<Void> webhook(
            @RequestBody byte[] body,
            @RequestHeader(value = "Stripe-Signature", required = false) String sig) throws Exception {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            return ResponseEntity.status(503).build();
        }
        if (!StripeSignature.isValid(webhookSecret, body, sig, Instant.now().getEpochSecond())) {
            return ResponseEntity.status(401).build();
        }
        webhookService.handle(objectMapper.readTree(body));
        return ResponseEntity.ok().build();
    }
}
