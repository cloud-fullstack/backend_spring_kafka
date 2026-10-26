package com.omero.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.omero.auth.entity.StripeSubscription;
import com.omero.auth.entity.SubscriptionRefund;
import com.omero.auth.entity.User;
import com.omero.auth.repository.SubscriptionRefundRepository;
import com.omero.auth.web.RefundNotAvailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SubscriptionRefundService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionRefundService.class);
    private static final List<String> USED = List.of("pending", "refunded", "canceled");
    private static final int MAX_ATTEMPTS = 6;

    public record RefundQuote(
            String subscriptionId,
            String invoiceId,
            String paymentIntentId,
            long amountCents,
            OffsetDateTime periodEnd) {}

    private final BillingAccountService accounts;
    private final SubscriptionStateService subscriptions;
    private final SubscriptionRefundRepository refunds;
    private final StripeApi stripeApi;
    private final TransactionTemplate tx;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final String mailFrom;
    private final String supportEmail;

    public SubscriptionRefundService(
            BillingAccountService accounts,
            SubscriptionStateService subscriptions,
            SubscriptionRefundRepository refunds,
            StripeApi stripeApi,
            PlatformTransactionManager transactionManager,
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${app.mail-from:}") String mailFrom,
            @Value("${app.support-email:support@telemaco.app}") String supportEmail) {
        this.accounts = accounts;
        this.subscriptions = subscriptions;
        this.refunds = refunds;
        this.stripeApi = stripeApi;
        this.tx = new TransactionTemplate(transactionManager);
        this.mailSender = mailSender;
        this.mailFrom = mailFrom == null ? "" : mailFrom.trim();
        this.supportEmail = supportEmail == null ? "" : supportEmail.trim();
    }

    public Map<String, Object> preview(UUID userId) {
        User user = accounts.requireUser(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        boolean yearlyUsed = refundedInLastYear(user.getTenantId());
        out.put("available", !yearlyUsed);
        StripeSubscription sub = subscriptions.livePlanSubscription(user.getTenantId()).orElse(null);
        RefundQuote q = sub == null ? null : quote(sub.getSubscriptionId());
        out.put("amountCents", q == null ? 0 : q.amountCents());
        out.put("periodEnd", q == null || q.periodEnd() == null ? null : q.periodEnd().toString());
        return out;
    }

    public Map<String, Object> cancelNowWithRefund(UUID userId, String reason) {
        User user = accounts.requireUser(userId);
        if (refundedInLastYear(user.getTenantId())) {
            throw new RefundNotAvailableException();
        }
        StripeSubscription sub = subscriptions.livePlanSubscription(user.getTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Invalid request"));
        RefundQuote q = quote(sub.getSubscriptionId());
        SubscriptionRefund row = tx.execute(status -> createPendingRow(user, sub, q, reason));
        try {
            return process(row);
        } catch (RuntimeException e) {
            log.warn("refund flow interrupted sub={}, job will resume", row.getSubscriptionId());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("refundCents", row.getAmountCents());
            out.put("status", "pending");
            return out;
        }
    }

    private SubscriptionRefund createPendingRow(
            User user, StripeSubscription sub, RefundQuote q, String reason) {
        SubscriptionRefund row = new SubscriptionRefund();
        row.setTenantId(user.getTenantId());
        row.setSubscriptionId(sub.getSubscriptionId());
        row.setInvoiceId(q == null ? "" : q.invoiceId());
        row.setPaymentIntentId(q == null ? "" : q.paymentIntentId());
        row.setAmountCents(q == null ? 0 : q.amountCents());
        row.setStatus("pending");
        row.setReason(reason);
        row.setAttempts(0);
        return refunds.saveAndFlush(row);
    }

    Map<String, Object> process(SubscriptionRefund row) {
        if (row.getAmountCents() > 0 && row.getStripeRefundId() == null) {
            MultiValueMap<String, String> f = new LinkedMultiValueMap<>();
            f.add("payment_intent", row.getPaymentIntentId());
            f.add("amount", Long.toString(row.getAmountCents()));
            f.add("reason", "requested_by_customer");
            f.add("metadata[kind]", "subscription_refund");
            f.add("metadata[subscription_id]", row.getSubscriptionId());
            f.add("metadata[tenant_id]", row.getTenantId().toString());
            JsonNode refund = stripeApi.post("/v1/refunds", f, "refund:" + row.getSubscriptionId());
            row.setStripeRefundId(StripeApi.text(refund.path("id")));
            row.setStatus("refunded");
            refunds.save(row);
        }
        stripeApi.delete("/v1/subscriptions/" + row.getSubscriptionId(), "cancel:" + row.getSubscriptionId());
        row.setStatus("canceled");
        refunds.save(row);
        refundAndCancelAddonOnly(row.getTenantId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("refundCents", row.getAmountCents());
        out.put("status", row.getStatus());
        return out;
    }

    @Scheduled(cron = "0 */10 * * * *")
    public void resumePending() {
        OffsetDateTime stale = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        for (SubscriptionRefund r : refunds.findByStatusInAndUpdatedAtBefore(List.of("pending", "refunded"), stale)) {
            try {
                process(r);
            } catch (Exception e) {
                r.setAttempts(r.getAttempts() + 1);
                if (r.getAttempts() >= MAX_ATTEMPTS) {
                    r.setStatus("failed");
                    notifySupport(r);
                }
                refunds.save(r);
                log.warn("refund resume failed sub={} attempts={}", r.getSubscriptionId(), r.getAttempts());
            }
        }
    }

    private void refundAndCancelAddonOnly(UUID tenantId) {
        for (StripeSubscription addon : subscriptions.liveAddonOnlySubscriptions(tenantId)) {
            RefundQuote q = quote(addon.getSubscriptionId());
            if (q != null && q.amountCents() > 0 && q.paymentIntentId() != null && !q.paymentIntentId().isBlank()) {
                MultiValueMap<String, String> f = new LinkedMultiValueMap<>();
                f.add("payment_intent", q.paymentIntentId());
                f.add("amount", Long.toString(q.amountCents()));
                f.add("reason", "requested_by_customer");
                f.add("metadata[kind]", "addon_refund");
                f.add("metadata[subscription_id]", addon.getSubscriptionId());
                stripeApi.post("/v1/refunds", f, "refund:addon:" + addon.getSubscriptionId());
            }
            stripeApi.delete(
                    "/v1/subscriptions/" + addon.getSubscriptionId(),
                    "cancel-addons:" + addon.getSubscriptionId());
        }
    }

    RefundQuote quote(String subscriptionId) {
        JsonNode sub = stripeApi.get("/v1/subscriptions/" + subscriptionId + "?expand[]=latest_invoice");
        if (sub == null) {
            return null;
        }
        JsonNode inv = sub.path("latest_invoice");
        if (inv.isTextual()) {
            return null;
        }
        if (!"paid".equals(StripeApi.text(inv.path("status")))) {
            return null;
        }
        long start = 0;
        long end = 0;
        for (JsonNode it : sub.path("items").path("data")) {
            start = Math.max(start, it.path("current_period_start").asLong(0));
            end = Math.max(end, it.path("current_period_end").asLong(0));
        }
        long now = Instant.now().getEpochSecond();
        if (end <= start || now >= end) {
            return null;
        }
        String invoiceId = StripeApi.text(inv.path("id"));
        String pi = stripeApi.firstPaidPaymentIntent(invoiceId);
        if (pi == null || pi.isBlank()) {
            return null;
        }
        JsonNode pay = stripeApi.get("/v1/payment_intents/" + pi + "?expand[]=latest_charge");
        if (pay == null) {
            return null;
        }
        long paid = pay.path("amount").asLong(0);
        if (paid <= 0) {
            paid = inv.path("amount_paid").asLong(0);
        }
        long alreadyRefunded = pay.path("latest_charge").path("amount_refunded").asLong(0);
        long amount = paid * (end - now) / (end - start) - alreadyRefunded;
        if (amount < 50) {
            return null;
        }
        return new RefundQuote(
                subscriptionId,
                invoiceId,
                pi,
                amount,
                Instant.ofEpochSecond(end).atOffset(ZoneOffset.UTC));
    }

    private boolean refundedInLastYear(UUID tenantId) {
        return refunds.existsByTenantIdAndCreatedAtAfterAndStatusInAndAmountCentsGreaterThan(
                tenantId, OffsetDateTime.now(ZoneOffset.UTC).minusYears(1), USED, 0L);
    }

    private void notifySupport(SubscriptionRefund row) {
        log.error("subscription refund failed permanently sub={} tenant={}", row.getSubscriptionId(), row.getTenantId());
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || mailFrom.isBlank() || supportEmail.isBlank()) {
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(mailFrom);
            msg.setTo(supportEmail);
            msg.setSubject("Refund job failed · " + row.getSubscriptionId());
            msg.setText(
                    "subscription=" + row.getSubscriptionId()
                            + "\ntenant=" + row.getTenantId()
                            + "\namountCents=" + row.getAmountCents()
                            + "\nstatus=" + row.getStatus());
            sender.send(msg);
        } catch (Exception e) {
            log.warn("refund failure mail not sent sub={}", row.getSubscriptionId());
        }
    }
}
