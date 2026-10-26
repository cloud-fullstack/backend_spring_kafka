package com.omero.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.omero.auth.entity.CreditPurchase;
import com.omero.auth.repository.BillingCommissionRepository;
import com.omero.auth.repository.CreditPurchaseRepository;
import com.omero.auth.repository.StripeWebhookEventRepository;
import com.omero.auth.repository.StripeInvoicePaymentRepository;
import com.omero.auth.repository.SubscriptionRefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class StripeWebhookService {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);

    private final StripeWebhookEventRepository events;
    private final CreditPurchaseRepository purchases;
    private final BillingAccountService accounts;
    private final SubscriptionStateService subscriptions;
    private final SubscriptionRefundRepository subscriptionRefunds;
    private final BillingCommissionRepository commissions;
    private final StripeInvoicePaymentRepository invoicePayments;
    private final boolean testMode;

    public StripeWebhookService(
            StripeWebhookEventRepository events,
            CreditPurchaseRepository purchases,
            BillingAccountService accounts,
            SubscriptionStateService subscriptions,
            SubscriptionRefundRepository subscriptionRefunds,
            BillingCommissionRepository commissions,
            StripeInvoicePaymentRepository invoicePayments,
            @Value("${stripe.test-mode:true}") boolean testMode) {
        this.events = events;
        this.purchases = purchases;
        this.accounts = accounts;
        this.subscriptions = subscriptions;
        this.subscriptionRefunds = subscriptionRefunds;
        this.commissions = commissions;
        this.invoicePayments = invoicePayments;
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
        OffsetDateTime at = eventAt(root);
        switch (type) {
            case "checkout.session.completed" -> {
                recordPurchase(obj);
                rememberCustomer(obj);
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
            case "charge.refunded" -> {
                String pi = text(obj.path("payment_intent"));
                long refunded = obj.path("amount_refunded").asLong(0);
                long charged = obj.path("amount").asLong(0);
                subscriptions.reverseCommission(pi, refunded, charged > 0 ? charged : refunded);
                reverse(pi, refunded, true, "refunded", at);
            }
            case "charge.dispute.created" -> reverse(
                    text(obj.path("payment_intent")),
                    obj.path("amount").asLong(0),
                    false,
                    "disputed",
                    at);
            case "charge.dispute.closed" -> {
                if ("won".equals(text(obj.path("status")))) {
                    restoreWon(text(obj.path("payment_intent")), obj.path("amount").asLong(0), at);
                }
            }
            case "customer.subscription.created", "customer.subscription.updated" ->
                    subscriptions.applyStripeObject(obj, at, false);
            case "customer.subscription.deleted" -> subscriptions.applyStripeObject(obj, at, true);
            case "invoice.paid" -> subscriptions.accrueStripeInvoice(obj);
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

    private void reverse(
            String paymentIntentId, long amount, boolean cumulative, String finalStatus, OffsetDateTime eventAt) {
        if (paymentIntentId == null || paymentIntentId.isBlank() || amount <= 0) {
            return;
        }
        CreditPurchase row = purchases.findByPaymentIntentId(paymentIntentId).orElse(null);
        if (row == null) {
            boolean subscriptionCharge = subscriptionRefunds.existsByPaymentIntentId(paymentIntentId)
                    || commissions.findByStripePaymentIntentId(paymentIntentId).isPresent()
                    || invoicePayments.existsByPaymentIntentId(paymentIntentId);
            if (!subscriptionCharge && isRecent(eventAt)) {
                throw new IllegalStateException("Stripe reverse before purchase: payment_intent=" + paymentIntentId);
            }
            log.info("Stripe {} without credit purchase payment_intent={}", finalStatus, paymentIntentId);
            return;
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

    private void restoreWon(String paymentIntentId, long amount, OffsetDateTime eventAt) {
        if (paymentIntentId == null || paymentIntentId.isBlank() || amount <= 0) {
            return;
        }
        CreditPurchase row = purchases.findByPaymentIntentId(paymentIntentId).orElse(null);
        if (row == null) {
            boolean subscriptionCharge = subscriptionRefunds.existsByPaymentIntentId(paymentIntentId)
                    || commissions.findByStripePaymentIntentId(paymentIntentId).isPresent()
                    || invoicePayments.existsByPaymentIntentId(paymentIntentId);
            if (!subscriptionCharge && isRecent(eventAt)) {
                throw new IllegalStateException("Stripe restore before purchase: payment_intent=" + paymentIntentId);
            }
            log.info("Stripe dispute won without credit purchase payment_intent={}", paymentIntentId);
            return;
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

    private void rememberCustomer(JsonNode session) {
        String tenantRaw = text(session.path("metadata").path("tenant_id"));
        if (tenantRaw.isBlank()) {
            return;
        }
        try {
            accounts.setStripeCustomerIfAbsent(UUID.fromString(tenantRaw), text(session.path("customer")));
        } catch (IllegalArgumentException ignored) {
            /* invalid tenant */
        }
    }

    private static boolean isRecent(OffsetDateTime eventAt) {
        if (eventAt == null) {
            return false;
        }
        return eventAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10));
    }

    private static OffsetDateTime eventAt(JsonNode root) {
        long created = root.path("created").asLong(0);
        if (created <= 0) {
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
        return Instant.ofEpochSecond(created).atOffset(ZoneOffset.UTC);
    }

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return "";
        }
        return n.asText("");
    }
}
