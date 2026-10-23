package com.omero.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omero.auth.repository.BillingCommissionRepository;
import com.omero.auth.repository.LemonWebhookEventRepository;
import com.omero.auth.repository.SubscriptionPlanRepository;
import com.omero.auth.repository.TenantRepository;
import com.omero.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LemonWebhookServiceTest {

    @Mock LemonWebhookEventRepository events;
    @Mock UserRepository users;
    @Mock TenantRepository tenants;
    @Mock SubscriptionPlanRepository plans;
    @Mock BillingCommissionRepository commissions;
    @Mock PlanAccessService planAccess;

    private final ObjectMapper mapper = new ObjectMapper();
    private LemonWebhookService dev;

    @BeforeEach
    void setUp() {
        dev = new LemonWebhookService(events, users, tenants, plans, commissions, planAccess, true);
    }

    @Test
    void ignoresLivePayloadWhenTestModeExpected() throws Exception {
        dev.handle(mapper.readTree("{\"meta\":{\"event_name\":\"subscription_created\",\"test_mode\":false}}"));
        verify(events, never()).insertIfAbsent(any(), any());
    }

    @Test
    void acceptsTestPayloadWhenTestModeExpected() throws Exception {
        org.mockito.Mockito.when(events.insertIfAbsent(any(), any())).thenReturn(1);
        dev.handle(mapper.readTree(
                "{\"meta\":{\"event_name\":\"order_created\",\"test_mode\":true},"
                        + "\"data\":{\"id\":\"1\",\"type\":\"orders\",\"attributes\":{}}}"));
        verify(events).insertIfAbsent(any(), any());
    }
}
