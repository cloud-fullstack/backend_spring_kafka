package com.omero.subscription.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omero.subscription.service.SubscriptionService;
import com.omero.subscription.service.PaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private ObjectMapper objectMapper;

    @PostMapping("/lemon-squeezy")
    public ResponseEntity<?> handleLemonSqueezyWebhook(
            @RequestBody String payload,
            HttpServletRequest request) throws IOException {

        // Verify signature (implement your verification logic here)
        String signature = request.getHeader("X-Lemon-Squeezy-Signature");
        if (!verifySignature(payload, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        JsonNode webhook = objectMapper.readTree(payload);
        String eventType = webhook.get("event").asText();

        switch (eventType) {
            case "subscription.created":
                handleSubscriptionCreated(webhook);
                break;
            case "subscription.updated":
                handleSubscriptionUpdated(webhook);
                break;
            case "subscription.cancelled":
                handleSubscriptionCancelled(webhook);
                break;
            case "payment.succeeded":
                handlePaymentSucceeded(webhook);
                break;
            case "payment.failed":
                handlePaymentFailed(webhook);
                break;
            default:
                // Log unknown event type
                break;
        }

        return ResponseEntity.ok().build();
    }

    private void handleSubscriptionCreated(JsonNode webhook) {
        JsonNode data = webhook.get("data");
        String subscriptionId = data.get("id").asText();
        String customerId = data.get("customer_id").asText();
        String planId = data.get("plan_id").asText();
        
        subscriptionService.createSubscription(
            customerId,
            planId,
            subscriptionId
        );
    }

    private void handleSubscriptionUpdated(JsonNode webhook) {
        JsonNode data = webhook.get("data");
        String subscriptionId = data.get("id").asText();
        String status = data.get("status").asText();
        
        subscriptionService.updateSubscriptionStatus(
            subscriptionId,
            status
        );
    }

    private void handleSubscriptionCancelled(JsonNode webhook) {
        JsonNode data = webhook.get("data");
        String subscriptionId = data.get("id").asText();
        
        subscriptionService.cancelSubscription(subscriptionId);
    }

    private void handlePaymentSucceeded(JsonNode webhook) {
        JsonNode data = webhook.get("data");
        String paymentId = data.get("id").asText();
        String subscriptionId = data.get("subscription_id").asText();
        double amount = data.get("amount").asDouble();
        String currency = data.get("currency").asText();
        
        paymentService.recordPayment(
            paymentId,
            subscriptionId,
            amount,
            currency,
            "succeeded"
        );
    }

    private void handlePaymentFailed(JsonNode webhook) {
        JsonNode data = webhook.get("data");
        String paymentId = data.get("id").asText();
        String subscriptionId = data.get("subscription_id").asText();
        
        paymentService.recordPayment(
            paymentId,
            subscriptionId,
            0.0,
            "",
            "failed"
        );
    }

    private boolean verifySignature(String payload, String signature) {
        // Implement your signature verification logic here
        return true; // TODO: Implement proper signature verification
    }
}
