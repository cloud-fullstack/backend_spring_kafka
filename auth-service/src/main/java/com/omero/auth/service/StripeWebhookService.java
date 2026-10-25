package com.omero.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.omero.auth.entity.CreditPurchase;
import com.omero.auth.repository.CreditPurchaseRepository;
import com.omero.auth.repository.StripeWebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class StripeWebhookService {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);

    private final StripeWebhookEventRepository events;
    private final CreditPurchaseRepository purchases;
    private final BillingAccountService accounts;
    private final boolean testMode;

    public StripeWebhookService(
            StripeWebhookEventRepository events,
            CreditPurchaseRepository purchases,
            BillingAccountService accounts,
            @Value("${stripe.test-mode:true}") boolean testMode) {
        this.events = events;
        this.purchases = purchases;
        this.accounts = accounts;
        this.testMode = testMode;
    }

    @Transactional
    public void handle(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return;
        }
        boolean live = root.path("livemode").asBoolean(false);
        if (live == testMode) {
            log.warn("Stripe webhook ignored: livemode={} expectedTest={}", live, testMode);
            return;
        }
        String eventId = text(root.path("id"));
        String type = text(root.path("type"));
        if (eventId.isBlank()) {
            return;
        }
        if (events.insertIfAbsent(eventId, type.isBlank() ? "unknown" : type) != 1) {
            return;
        }
        JsonNode obj = root.path("data").path("object");
        switch (type) {
            case "checkout.session.completed" -> {
                recordPurchase(obj);
                if ("paid".equals(text(obj.path("payment_status")))) {
                    creditOnce(obj);
                }
            }
            case "checkout.session.async_payment_succeeded" -> {
                recordPurchase(obj);
                creditOnce(obj);
            }
            case "checkout.session.async_payment_failed" -> {
                recordPurchase(obj);
                markFailed(obj);
            }
            case "charge.refunded" -> reverse(
                    text(obj.path("payment_intent")),
                    obj.path("amount_refunded").asLong(0),
                    true,
                    "refunded");
            case "charge.dispute.created" -> reverse(
                    text(obj.path("payment_intent")),
                    obj.path("amount").asLong(0),
                    false,
                    "disputed");
            case "charge.dispute.closed" -> {
                if ("won".equals(text(obj.path("status")))) {
                    restoreWon(text(obj.path("payment_intent")), obj.path("amount").asLong(0));
                }
            }
            default -> { }
        }
    }

    private void recordPurchase(JsonNode session) {
        if (!"credits".equals(text(session.path("metadata").path("kind")))) {
            return;
        }
        String sid = text(session.path("id"));
        String tenantRaw = text(session.path("metadata").path("tenant_id"));
        if (sid.isBlank()) {
            return;
        }
        UUID tenantId;
        try {
            tenantId = UUID.fromString(tenantRaw);
        } catch (IllegalArgumentException e) {
            log.warn("Stripe checkout without tenant_id session={}", sid);
            return;
        }
        long credits = session.path("metadata").path("credit_cents").asLong(0);
        if (credits <= 0) {
            log.warn("Stripe checkout missing metadata.credit_cents session={}", sid);
            return;
        }
        String pi = text(session.path("payment_intent"));
        long total = session.path("amount_total").asLong(0);
        purchases.upsertPending(sid, tenantId, pi.isBlank() ? null : pi, credits, total);
        accounts.setStripeCustomerIfAbsent(tenantId, text(session.path("customer")));
    }

    private void creditOnce(JsonNode session) {
        String sid = text(session.path("id"));
        if (sid.isBlank()) {
            return;
        }
        if (purchases.markPaidIfPending(sid) != 1) {
            return;
        }
        CreditPurchase row = purchases.findById(sid).orElse(null);
        if (row == null || row.getCreditCents() <= 0) {
            return;
        }
        accounts.addCredits(row.getTenantId(), row.getCreditCents());
    }

    private void markFailed(JsonNode session) {
        String sid = text(session.path("id"));
        if (!sid.isBlank()) {
            purchases.markFailedIfPending(sid);
        }
    }

    private void reverse(String paymentIntentId, long amount, boolean cumulative, String finalStatus) {
        if (paymentIntentId == null || paymentIntentId.isBlank() || amount <= 0) {
            return;
        }
        CreditPurchase row = purchases.findByPaymentIntentId(paymentIntentId).orElse(null);
        if (row == null) {
            throw new IllegalStateException("Stripe reverse before purchase: payment_intent=" + paymentIntentId);
        }
        if ("pending".equals(row.getStatus()) || "failed".equals(row.getStatus())) {
            row.setStatus(finalStatus);
            purchases.save(row);
            return;
        }
        // amount and amount_total must be the same currency (Adaptive Pricing).
        long total = row.getAmountTotalCents();
        if (total <= 0) {
            return;
        }
        long share = amount * row.getCreditCents() / total;
        long target = cumulative
                ? Math.min(row.getCreditCents(), share)
                : Math.min(row.getCreditCents(), row.getReversedCents() + share);
        long delta = target - row.getReversedCents();
        if (delta <= 0) {
            return;
        }
        row.setReversedCents(row.getReversedCents() + delta);
        if (row.getReversedCents() >= row.getCreditCents()) {
            row.setStatus(finalStatus);
        }
        purchases.save(row);
        accounts.addCredits(row.getTenantId(), -delta);
    }

    private void restoreWon(String paymentIntentId, long amount) {
        if (paymentIntentId == null || paymentIntentId.isBlank() || amount <= 0) {
            return;
        }
        CreditPurchase row = purchases.findByPaymentIntentId(paymentIntentId).orElse(null);
        if (row == null) {
            throw new IllegalStateException("Stripe restore before purchase: payment_intent=" + paymentIntentId);
        }
        long total = row.getAmountTotalCents();
        if (total <= 0 || row.getReversedCents() <= 0) {
            return;
        }
        long share = amount * row.getCreditCents() / total;
        long delta = Math.min(row.getReversedCents(), share);
        if (delta <= 0) {
            return;
        }
        row.setReversedCents(row.getReversedCents() - delta);
        if (row.getReversedCents() < row.getCreditCents()) {
            row.setStatus("paid");
        }
        purchases.save(row);
        accounts.addCredits(row.getTenantId(), delta);
    }

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return "";
        }
        return n.asText("");
    }
}
