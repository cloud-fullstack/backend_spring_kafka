package com.omero.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omero.auth.entity.CreditPurchase;
import com.omero.auth.repository.BillingCommissionRepository;
import com.omero.auth.repository.CreditPurchaseRepository;
import com.omero.auth.repository.StripeWebhookEventRepository;
import com.omero.auth.repository.StripeInvoicePaymentRepository;
import com.omero.auth.repository.SubscriptionRefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StripeWebhookServiceTest {

    @Mock StripeWebhookEventRepository events;
    @Mock CreditPurchaseRepository purchases;
    @Mock BillingAccountService accounts;
    @Mock SubscriptionStateService subscriptions;
    @Mock SubscriptionRefundRepository subscriptionRefunds;
    @Mock BillingCommissionRepository commissions;
    @Mock StripeInvoicePaymentRepository invoicePayments;

    private final ObjectMapper mapper = new ObjectMapper();
    private StripeWebhookService testMode;
    private final UUID tenant = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        testMode = new StripeWebhookService(
                events, purchases, accounts, subscriptions, subscriptionRefunds, commissions, invoicePayments, true);
        lenient().when(subscriptionRefunds.existsByPaymentIntentId(anyString())).thenReturn(false);
        lenient().when(commissions.findByStripePaymentIntentId(anyString())).thenReturn(Optional.empty());
        lenient().when(invoicePayments.existsByPaymentIntentId(anyString())).thenReturn(false);
    }

    @Test
    void ignoresLiveWhenTestModeExpected() throws Exception {
        testMode.handle(mapper.readTree("{\"id\":\"evt_1\",\"livemode\":true,\"type\":\"checkout.session.completed\"}"));
        verify(events, never()).insertIfAbsent(any(), any());
    }

    @Test
    void doesNotCreditUnpaidCompleted() throws Exception {
        when(events.insertIfAbsent(any(), any())).thenReturn(1);
        testMode.handle(mapper.readTree(session("checkout.session.completed", "unpaid", 3050, 2500)));
        verify(purchases).upsertPending(eq("cs_1"), eq(tenant), eq("pi_1"), eq(2500L), eq(3050L));
        verify(purchases, never()).markPaidIfPending(anyString());
        verify(accounts, never()).addCredits(any(), anyLong());
    }

    @Test
    void creditsMetadataNotAmountTotal() throws Exception {
        when(events.insertIfAbsent(any(), any())).thenReturn(1);
        when(purchases.markPaidIfPending("cs_1")).thenReturn(1);
        CreditPurchase row = new CreditPurchase();
        row.setCheckoutSessionId("cs_1");
        row.setTenantId(tenant);
        row.setCreditCents(2500);
        when(purchases.findById("cs_1")).thenReturn(Optional.of(row));
        testMode.handle(mapper.readTree(session("checkout.session.completed", "paid", 3050, 2500)));
        verify(accounts).addCredits(tenant, 2500);
    }

    @Test
    void asyncSucceededCreditsOnce() throws Exception {
        when(events.insertIfAbsent(any(), any())).thenReturn(1);
        when(purchases.markPaidIfPending("cs_1")).thenReturn(0);
        testMode.handle(mapper.readTree(session("checkout.session.async_payment_succeeded", "paid", 3050, 2500)));
        verify(accounts, never()).addCredits(any(), anyLong());
    }

    @Test
    void refundReversesProportionalCredits() throws Exception {
        when(events.insertIfAbsent(any(), any())).thenReturn(1);
        CreditPurchase row = new CreditPurchase();
        row.setTenantId(tenant);
        row.setPaymentIntentId("pi_1");
        row.setCreditCents(2500);
        row.setAmountTotalCents(3050);
        row.setReversedCents(0);
        row.setStatus("paid");
        when(purchases.findByPaymentIntentId("pi_1")).thenReturn(Optional.of(row));
        testMode.handle(mapper.readTree("""
                {"id":"evt_r","livemode":false,"type":"charge.refunded",
                 "data":{"object":{"payment_intent":"pi_1","amount":3050,"amount_refunded":3050}}}
                """));
        verify(accounts).addCredits(tenant, -2500);
    }

    @Test
    void reverseBeforePurchaseThrowsSoStripeRetries() throws Exception {
        when(events.insertIfAbsent(any(), any())).thenReturn(1);
        when(purchases.findByPaymentIntentId("pi_1")).thenReturn(Optional.empty());
        long now = java.time.Instant.now().getEpochSecond();
        assertThrows(IllegalStateException.class, () -> testMode.handle(mapper.readTree("""
                {"id":"evt_r","created":%d,"livemode":false,"type":"charge.refunded",
                 "data":{"object":{"payment_intent":"pi_1","amount":3050,"amount_refunded":3050}}}
                """.formatted(now))));
        verify(accounts, never()).addCredits(any(), anyLong());
    }

    @Test
    void subscriptionRefundDoesNotThrow() throws Exception {
        when(events.insertIfAbsent(any(), any())).thenReturn(1);
        when(purchases.findByPaymentIntentId("pi_sub")).thenReturn(Optional.empty());
        when(subscriptionRefunds.existsByPaymentIntentId("pi_sub")).thenReturn(true);
        long now = java.time.Instant.now().getEpochSecond();
        testMode.handle(mapper.readTree("""
                {"id":"evt_r","created":%d,"livemode":false,"type":"charge.refunded",
                 "data":{"object":{"payment_intent":"pi_sub","amount":4500,"amount_refunded":2250}}}
                """.formatted(now)));
        verify(accounts, never()).addCredits(any(), anyLong());
        verify(subscriptions).reverseCommission("pi_sub", 2250, 4500);
    }

    @Test
    void reverseOnPendingDoesNotDebit() throws Exception {
        when(events.insertIfAbsent(any(), any())).thenReturn(1);
        CreditPurchase row = new CreditPurchase();
        row.setTenantId(tenant);
        row.setPaymentIntentId("pi_1");
        row.setCreditCents(2500);
        row.setAmountTotalCents(3050);
        row.setStatus("pending");
        when(purchases.findByPaymentIntentId("pi_1")).thenReturn(Optional.of(row));
        testMode.handle(mapper.readTree("""
                {"id":"evt_r","livemode":false,"type":"charge.refunded",
                 "data":{"object":{"payment_intent":"pi_1","amount":3050,"amount_refunded":3050}}}
                """));
        verify(purchases).save(row);
        verify(accounts, never()).addCredits(any(), anyLong());
    }

    @Test
    void disputeWonRestoresCredits() throws Exception {
        when(events.insertIfAbsent(any(), any())).thenReturn(1);
        CreditPurchase row = new CreditPurchase();
        row.setTenantId(tenant);
        row.setPaymentIntentId("pi_1");
        row.setCreditCents(2500);
        row.setAmountTotalCents(3050);
        row.setReversedCents(2500);
        row.setStatus("disputed");
        when(purchases.findByPaymentIntentId("pi_1")).thenReturn(Optional.of(row));
        testMode.handle(mapper.readTree("""
                {"id":"evt_w","livemode":false,"type":"charge.dispute.closed",
                 "data":{"object":{"payment_intent":"pi_1","amount":3050,"status":"won"}}}
                """));
        verify(accounts).addCredits(tenant, 2500);
    }

    @Test
    void refundAfterWonDisputeStillDebits() throws Exception {
        when(events.insertIfAbsent(any(), any())).thenReturn(1);
        CreditPurchase row = new CreditPurchase();
        row.setTenantId(tenant);
        row.setPaymentIntentId("pi_1");
        row.setCreditCents(2500);
        row.setAmountTotalCents(3050);
        row.setReversedCents(1000);
        row.setStatus("disputed");
        when(purchases.findByPaymentIntentId("pi_1")).thenReturn(Optional.of(row));
        testMode.handle(mapper.readTree("""
                {"id":"evt_r2","livemode":false,"type":"charge.refunded",
                 "data":{"object":{"payment_intent":"pi_1","amount":3050,"amount_refunded":3050}}}
                """));
        verify(accounts).addCredits(tenant, -1500);
    }

    private String session(String type, String paymentStatus, long amountTotal, long creditCents) {
        return """
                {"id":"evt_1","livemode":false,"type":"%s",
                 "data":{"object":{"id":"cs_1","payment_intent":"pi_1","payment_status":"%s",
                   "amount_total":%d,"customer":"cus_1",
                   "metadata":{"kind":"credits","tenant_id":"%s","credit_cents":"%d"}}}}
                """.formatted(type, paymentStatus, amountTotal, tenant, creditCents);
    }
}
