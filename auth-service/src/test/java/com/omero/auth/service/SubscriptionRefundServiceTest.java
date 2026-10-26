package com.omero.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.omero.auth.entity.SubscriptionRefund;
import com.omero.auth.repository.SubscriptionRefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionRefundServiceTest {

    @Mock BillingAccountService accounts;
    @Mock SubscriptionStateService subscriptions;
    @Mock SubscriptionRefundRepository refunds;
    @Mock StripeApi stripeApi;
    @Mock PlatformTransactionManager tm;
    @Mock ObjectProvider<JavaMailSender> mailSender;

    private final ObjectMapper mapper = new ObjectMapper();
    private SubscriptionRefundService svc;

    @BeforeEach
    void setUp() {
        lenient().when(tm.getTransaction(any())).thenReturn(org.mockito.Mockito.mock(TransactionStatus.class));
        lenient().when(refunds.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(subscriptions.liveAddonOnlySubscriptions(any())).thenReturn(java.util.List.of());
        svc = new SubscriptionRefundService(
                accounts, subscriptions, refunds, stripeApi, tm, mailSender, "", "support@telemaco.app");
    }

    @Test
    void processKeepsRefundedWhenDeleteFails() {
        SubscriptionRefund row = pending();
        ObjectNode refund = mapper.createObjectNode();
        refund.put("id", "re_1");
        when(stripeApi.post(eq("/v1/refunds"), any(), eq("refund:sub_1"))).thenReturn(refund);
        doThrow(new IllegalStateException("delete failed"))
                .when(stripeApi).delete("/v1/subscriptions/sub_1", "cancel:sub_1");

        assertThrows(IllegalStateException.class, () -> svc.process(row));
        assertEquals("refunded", row.getStatus());
        assertEquals("re_1", row.getStripeRefundId());

        org.mockito.Mockito.reset(stripeApi);
        svc.process(row);
        verify(stripeApi, never()).post(eq("/v1/refunds"), any(), any());
        verify(stripeApi).delete("/v1/subscriptions/sub_1", "cancel:sub_1");
        assertEquals("canceled", row.getStatus());
    }

    @Test
    void resumePendingMarksFailedAfterSixAttempts() {
        SubscriptionRefund row = pending();
        row.setAttempts(5);
        when(refunds.findByStatusInAndUpdatedAtBefore(any(), any())).thenReturn(java.util.List.of(row));
        doThrow(new IllegalStateException("already refunded"))
                .when(stripeApi).post(eq("/v1/refunds"), any(), eq("refund:sub_1"));
        svc.resumePending();
        assertEquals("failed", row.getStatus());
        assertEquals(6, row.getAttempts());
    }

    @Test
    void quoteBlankPaymentIntentDoesNotCallStripe() throws Exception {
        when(stripeApi.get(contains("/v1/subscriptions/sub_1"))).thenReturn(mapper.readTree("""
                {"id":"sub_1","latest_invoice":{"id":"in_1","status":"paid","amount_paid":4500},
                 "items":{"data":[{"current_period_start":1,"current_period_end":2000000000}]}}
                """));
        when(stripeApi.firstPaidPaymentIntent("in_1")).thenReturn("");
        assertNull(svc.quote("sub_1"));
        verify(stripeApi, never()).get(contains("/v1/payment_intents/"));
    }

    private static SubscriptionRefund pending() {
        SubscriptionRefund row = new SubscriptionRefund();
        row.setTenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        row.setSubscriptionId("sub_1");
        row.setInvoiceId("in_1");
        row.setPaymentIntentId("pi_1");
        row.setAmountCents(2250);
        row.setStatus("pending");
        return row;
    }
}
