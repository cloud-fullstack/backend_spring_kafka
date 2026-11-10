package com.omero.auth.service;

import com.omero.auth.entity.User;
import com.omero.auth.entity.WhatsAppNumberRequest;
import com.omero.auth.repository.UserRepository;
import com.omero.auth.repository.WhatsAppNumberRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JollyCheckoutServiceTest {

    @Mock BillingAccountService accounts;
    @Mock WhatsAppNumberRequestRepository numberRequests;
    @Mock StripeCheckoutService stripeCheckout;
    @Mock UserRepository users;
    @Mock ObjectProvider<JavaMailSender> mailSender;

    private JollyCheckoutService svc;
    private final UUID userId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID requestId = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @BeforeEach
    void setUp() {
        svc = new JollyCheckoutService(
                accounts, numberRequests, stripeCheckout, users, mailSender, "", "support@telemaco.app");
    }

    @Test
    void checkoutDirectDelegates() {
        when(stripeCheckout.jollyCheckoutUrl(userId, null)).thenReturn("https://checkout.stripe.com/jolly");
        assertEquals("https://checkout.stripe.com/jolly", svc.checkoutDirect(userId));
        verify(numberRequests, never()).save(any());
    }

    @Test
    void checkoutWithSetupSavesDraftAndSession() {
        User user = new User();
        user.setId(userId);
        user.setTenantId(tenantId);
        when(accounts.requireUser(userId)).thenReturn(user);
        when(numberRequests.existsByTenantIdAndStatusIn(any(), any())).thenReturn(false);
        when(numberRequests.save(any())).thenAnswer(inv -> {
            WhatsAppNumberRequest row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(requestId);
            }
            return row;
        });
        when(stripeCheckout.createJollyCheckout(userId, requestId))
                .thenReturn(new StripeCheckoutService.CreatedCheckout("https://checkout.stripe.com/setup", "cs_1"));

        JollySetupRequest body = new JollySetupRequest(
                false, "Acme", "IT123", "Via Roma 1", "Milano", "Studio Acme", true);
        assertEquals("https://checkout.stripe.com/setup", svc.checkoutWithSetup(userId, body));

        ArgumentCaptor<WhatsAppNumberRequest> cap = ArgumentCaptor.forClass(WhatsAppNumberRequest.class);
        verify(numberRequests, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        WhatsAppNumberRequest last = cap.getValue();
        assertEquals("pending_payment", last.getStatus());
        assertEquals("cs_1", last.getStripeSessionId());
        assertEquals("Acme", last.getCompanyName());
    }

    @Test
    void checkoutWithSetupRejectsOpenRequest() {
        User user = new User();
        user.setTenantId(tenantId);
        when(accounts.requireUser(userId)).thenReturn(user);
        when(numberRequests.existsByTenantIdAndStatusIn(any(), any())).thenReturn(true);
        JollySetupRequest body = new JollySetupRequest(
                false, "Acme", null, "Via Roma 1", null, "Studio", true);
        assertThrows(IllegalStateException.class, () -> svc.checkoutWithSetup(userId, body));
        verify(stripeCheckout, never()).createJollyCheckout(any(), any());
    }

    @Test
    void markRequestedMovesPendingPayment() {
        WhatsAppNumberRequest row = new WhatsAppNumberRequest();
        row.setId(requestId);
        row.setTenantId(tenantId);
        row.setStatus("pending_payment");
        row.setCompanyName("Acme");
        row.setAddress("Via");
        row.setDisplayName("Acme");
        when(numberRequests.findById(requestId)).thenReturn(Optional.of(row));
        when(users.findByTenantId(tenantId)).thenReturn(List.of());

        svc.markRequested(requestId, "cs_1");

        assertEquals("requested", row.getStatus());
        assertEquals("cs_1", row.getStripeSessionId());
        verify(numberRequests).save(row);
    }

    @Test
    void markRequestedFallsBackToStripeSessionId() {
        WhatsAppNumberRequest row = new WhatsAppNumberRequest();
        row.setId(requestId);
        row.setTenantId(tenantId);
        row.setStatus("pending_payment");
        row.setCompanyName("Acme");
        row.setAddress("Via");
        row.setDisplayName("Acme");
        row.setStripeSessionId("cs_1");
        when(numberRequests.findByStripeSessionId("cs_1")).thenReturn(Optional.of(row));
        when(users.findByTenantId(tenantId)).thenReturn(List.of());

        svc.markRequested(null, "cs_1");

        assertEquals("requested", row.getStatus());
        verify(numberRequests).save(row);
        verify(numberRequests, never()).findById(any());
    }
}
