package com.omero.auth.controller;

import com.omero.auth.entity.SubscriptionPlan;
import com.omero.auth.repository.SubscriptionPlanRepository;
import com.omero.auth.service.BillingAccountService;
import com.omero.auth.service.LemonCheckoutService;
import com.omero.auth.service.StripeCheckoutService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private final LemonCheckoutService checkoutService;
    private final StripeCheckoutService stripeCheckout;
    private final BillingAccountService billingAccounts;
    private final SubscriptionPlanRepository planRepository;

    public BillingController(
            LemonCheckoutService checkoutService,
            StripeCheckoutService stripeCheckout,
            BillingAccountService billingAccounts,
            SubscriptionPlanRepository planRepository) {
        this.checkoutService = checkoutService;
        this.stripeCheckout = stripeCheckout;
        this.billingAccounts = billingAccounts;
        this.planRepository = planRepository;
    }

    @GetMapping("/plans")
    public List<Map<String, Object>> plans() {
        return planRepository.findAll().stream().map(this::toPublic).toList();
    }

    @PostMapping("/checkout")
    public ResponseEntity<Map<String, String>> checkout(@RequestBody @Valid CheckoutRequest request) {
        String url = checkoutService.checkoutUrl(currentUserId(), request.getPlanCode());
        return ResponseEntity.ok(Map.of("url", url));
    }

    @GetMapping("/account")
    public Map<String, Object> account() {
        return billingAccounts.snapshot(currentUserId());
    }

    @PostMapping("/credits")
    public ResponseEntity<Map<String, String>> credits(@RequestBody @Valid CreditsRequest request) {
        long cents = Math.round(request.getAmountEur() * 100);
        String url = stripeCheckout.creditsCheckoutUrl(currentUserId(), cents);
        return ResponseEntity.ok(Map.of("url", url));
    }

    private Map<String, Object> toPublic(SubscriptionPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", p.getCode());
        m.put("name", p.getName());
        m.put("price", p.getPrice());
        return m;
    }

    private UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UUID uid)) {
            throw new SecurityException("Invalid credentials");
        }
        return uid;
    }

    @Data
    public static class CheckoutRequest {
        @NotBlank
        private String planCode;
    }

    @Data
    public static class CreditsRequest {
        @DecimalMin("5")
        @DecimalMax("50000")
        private double amountEur;
    }
}
