package com.omero.auth.service;

import com.omero.auth.entity.TenantAddon;
import com.omero.auth.repository.SubscriptionPlanRepository;
import com.omero.auth.repository.TenantAddonRepository;
import com.omero.auth.repository.TenantRepository;
import com.omero.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelEntitlementClientTest {

    @Mock RestTemplate http;
    @Mock SubscriptionPlanRepository plans;
    @Mock TenantAddonRepository addons;
    @Mock UserRepository users;
    @Mock TenantRepository tenants;

    private ChannelEntitlementClient client;
    private final UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        client = new ChannelEntitlementClient(http, plans, addons, users, tenants, "", "", "", "dev", "");
    }

    @Test
    void activateAddonStealsInactiveManualRow() {
        TenantAddon existing = new TenantAddon();
        existing.setTenantId(tenantId);
        existing.setAddonCode("whatsapp");
        existing.setSource("manual");
        existing.setStatus("inactive");
        when(addons.findById(any())).thenReturn(Optional.of(existing));

        client.activateAddon(tenantId, "whatsapp", "stripe");

        ArgumentCaptor<TenantAddon> cap = ArgumentCaptor.forClass(TenantAddon.class);
        verify(addons).save(cap.capture());
        assertEquals("stripe", cap.getValue().getSource());
        assertEquals("active", cap.getValue().getStatus());
    }

    @Test
    void activateAddonLeavesOtherActiveSource() {
        TenantAddon existing = new TenantAddon();
        existing.setTenantId(tenantId);
        existing.setAddonCode("whatsapp");
        existing.setSource("lemon");
        existing.setStatus("active");
        when(addons.findById(any())).thenReturn(Optional.of(existing));

        client.activateAddon(tenantId, "whatsapp", "stripe");

        verify(addons, never()).save(any());
    }
}
