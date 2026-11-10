package com.omero.auth.controller;

import com.omero.auth.entity.SubscriptionPlan;
import com.omero.auth.repository.SubscriptionPlanRepository;
import com.omero.auth.service.BillingAccountService;
import com.omero.auth.service.JollyCheckoutService;
import com.omero.auth.service.JollySetupRequest;
import com.omero.auth.service.LemonCheckoutService;
import com.omero.auth.service.PlanWaitlistService;
import com.omero.auth.service.StripeCheckoutService;
import com.omero.auth.service.SubscriptionRefundService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final JollyCheckoutService jollyCheckout;
    private final BillingAccountService billingAccounts;
    private final SubscriptionPlanRepository planRepository;
    private final PlanWaitlistService waitlist;
    private final SubscriptionRefundService refunds;
    private final String plansProvider;

    public BillingController(
            LemonCheckoutService checkoutService,
            StripeCheckoutService stripeCheckout,
            JollyCheckoutService jollyCheckout,
            BillingAccountService billingAccounts,
            SubscriptionPlanRepository planRepository,
            PlanWaitlistService waitlist,
            SubscriptionRefundService refunds,
            @Value("${billing.plans-provider:stripe}") String plansProvider) {
        this.checkoutService = checkoutService;
        this.stripeCheckout = stripeCheckout;
        this.jollyCheckout = jollyCheckout;
        this.billingAccounts = billingAccounts;
        this.planRepository = planRepository;
        this.waitlist = waitlist;
        this.refunds = refunds;
        this.plansProvider = plansProvider == null ? "stripe" : plansProvider;
    }

    @GetMapping("/plans")
    public Map<String, Object> plans() {
        List<Map<String, Object>> paid = new java.util.ArrayList<>();
        List<Map<String, Object>> addons = new java.util.ArrayList<>();
        for (SubscriptionPlan p : planRepository.findAllByOrderBySortOrderAsc()) {
            if (p.getCode() == null || "demo".equalsIgnoreCase(p.getCode())
                    || "whatsapp_new_number".equalsIgnoreCase(p.getCode())
                    || "whatsapp_number_setup".equalsIgnoreCase(p.getCode())) {
                continue;
            }
            Map<String, Object> row = toPublic(p);
            if (isAddon(p)) {
                addons.add(row);
            } else {
                paid.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plans", paid);
        out.put("addons", addons);
        return out;
    }

    @PostMapping("/checkout")
    public ResponseEntity<Map<String, String>> checkout(@RequestBody @Valid CheckoutRequest request) {
        String url;
        if ("lemon".equalsIgnoreCase(plansProvider)) {
            url = checkoutService.checkoutUrl(currentUserId(), request.getPlanCode());
        } else {
            url = stripeCheckout.subscriptionCheckoutUrl(
                    currentUserId(), request.getPlanCode(), request.getAddons());
        }
        return ResponseEntity.ok(Map.of("url", url));
    }

    @PostMapping("/waitlist")
    public ResponseEntity<Map<String, Object>> waitlist(
            @RequestBody @Valid WaitlistRequest request,
            HttpServletRequest http) {
        UUID tenant = optionalTenantId();
        String email = request.getEmail();
        if ((email == null || email.isBlank()) && tenant != null) {
            email = billingAccounts.requireUser(currentUserId()).getEmail();
        }
        waitlist.join(email, request.getPlanCode(), tenant, http);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/checkout/session")
    public Map<String, Object> checkoutSession(@RequestParam("session_id") String sessionId) {
        return stripeCheckout.checkoutSessionSummary(currentUserId(), sessionId);
    }

    @GetMapping("/account")
    public Map<String, Object> account() {
        Map<String, Object> snap = billingAccounts.snapshot(currentUserId());
        try {
            Object sub = snap.get("subscription");
            if (sub instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> writable = (Map<String, Object>) sub;
                writable.putAll(stripeCheckout.paymentMethodInfo(currentUserId()));
            }
        } catch (RuntimeException ignored) {
            // snapshot resta senza carta
        }
        return snap;
    }

    @PostMapping("/portal")
    public ResponseEntity<Map<String, String>> portal() {
        if ("lemon".equalsIgnoreCase(plansProvider)) {
            return ResponseEntity.ok(Map.of("url", checkoutService.customerPortalUrl()));
        }
        return ResponseEntity.ok(Map.of("url", stripeCheckout.billingPortalUrl(currentUserId())));
    }

    @GetMapping("/usage")
    public Map<String, Object> usage() {
        return billingAccounts.usage(currentUserId());
    }

    @PostMapping("/subscription/cancel")
    public Map<String, Object> cancel(@RequestBody(required = false) CancelRequest request) {
        String reason = request == null ? null : request.getReason();
        return stripeCheckout.scheduleCancel(currentUserId(), reason);
    }

    @PostMapping("/subscription/resume")
    public Map<String, Object> resume() {
        return stripeCheckout.resume(currentUserId());
    }

    @GetMapping("/subscription/refund-preview")
    public Map<String, Object> refundPreview() {
        return refunds.preview(currentUserId());
    }

    @PostMapping("/subscription/cancel-now")
    public Map<String, Object> cancelNow(@RequestBody(required = false) CancelRequest request) {
        String reason = request == null ? null : request.getReason();
        return refunds.cancelNowWithRefund(currentUserId(), reason);
    }

    @PostMapping("/ical")
    public ResponseEntity<Map<String, String>> ical() {
        return ResponseEntity.ok(Map.of("url", stripeCheckout.icalCheckoutUrl(currentUserId())));
    }

    @PostMapping("/one-off")
    public ResponseEntity<Map<String, String>> oneOff(@RequestBody @Valid CheckoutRequest request) {
        return ResponseEntity.ok(Map.of(
                "url", stripeCheckout.oneOffCheckoutUrl(currentUserId(), request.getPlanCode())));
    }

    @PostMapping("/jolly")
    public ResponseEntity<Map<String, String>> jolly(@RequestBody(required = false) JollySetupRequest body) {
        try {
            String url = body == null || body.hasBusinessNumber()
                    ? jollyCheckout.checkoutDirect(currentUserId())
                    : jollyCheckout.checkoutWithSetup(currentUserId(), body);
            return ResponseEntity.ok(Map.of("url", url));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "" : e.getMessage()));
        }
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
        m.put("features", p.getFeatures() == null ? Map.of() : p.getFeatures());
        return m;
    }

    private static boolean isAddon(SubscriptionPlan p) {
        if (p.getFeatures() == null) {
            return false;
        }
        Object kind = p.getFeatures().get("kind");
        return kind != null && "addon".equalsIgnoreCase(kind.toString());
    }

    private UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UUID uid)) {
            throw new SecurityException("Invalid credentials");
        }
        return uid;
    }

    private UUID optionalTenantId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UUID uid)) {
            return null;
        }
        try {
            return billingAccounts.requireUser(uid).getTenantId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Data
    public static class CheckoutRequest {
        @NotBlank
        private String planCode;
        private java.util.List<String> addons;
    }

    @Data
    public static class CreditsRequest {
        @DecimalMin("5")
        @DecimalMax("50000")
        private double amountEur;
    }

    @Data
    public static class WaitlistRequest {
        private String email;
        @NotBlank
        private String planCode;
    }

    @Data
    public static class CancelRequest {
        private String reason;
    }
}
