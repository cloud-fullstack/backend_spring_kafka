package com.omero.auth.service;

import com.omero.auth.entity.SubscriptionPlan;
import com.omero.auth.entity.Tenant;
import com.omero.auth.entity.User;
import com.omero.auth.repository.SubscriptionPlanRepository;
import com.omero.auth.repository.TenantAddonRepository;
import com.omero.auth.repository.TenantRepository;
import com.omero.auth.web.PlanNotAvailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StripeCheckoutServiceTest {

    @Mock BillingAccountService accounts;
    @Mock TenantRepository tenants;
    @Mock TenantAddonRepository tenantAddons;
    @Mock SubscriptionPlanRepository plans;
    @Mock SubscriptionStateService subscriptions;
    @Mock LemonCheckoutService lemonCheckout;
    @Mock StripeApi stripeApi;

    private StripeCheckoutService svc;
    private final UUID userId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        svc = new StripeCheckoutService(
                accounts, tenants, tenantAddons, plans, subscriptions, lemonCheckout, stripeApi,
                "txcd_10105002", "https://omero.dev");
    }

    @Test
    void addonCheckoutRejectedForDemoTenant() {
        User user = new User();
        user.setId(userId);
        user.setTenantId(tenantId);
        user.setPlanId("demo");
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setStatus("demo");
        SubscriptionPlan wa = new SubscriptionPlan();
        wa.setCode("whatsapp");
        wa.setStripePriceId("price_wa");
        wa.setFeatures(Map.of("kind", "addon", "status", "available"));
        when(accounts.requireUser(userId)).thenReturn(user);
        when(plans.findByCodeIgnoreCase("whatsapp")).thenReturn(Optional.of(wa));
        when(tenants.findById(tenantId)).thenReturn(Optional.of(tenant));

        assertThrows(PlanNotAvailableException.class,
                () -> svc.subscriptionCheckoutUrl(userId, "whatsapp", List.of()));
    }

    @Test
    void secondAddonPurchaseRejected() {
        User user = new User();
        user.setId(userId);
        user.setTenantId(tenantId);
        user.setPlanId("silver");
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setStatus("active");
        SubscriptionPlan wa = new SubscriptionPlan();
        wa.setCode("whatsapp");
        wa.setStripePriceId("price_wa");
        wa.setFeatures(Map.of("kind", "addon", "status", "available"));
        com.omero.auth.entity.TenantAddon row = new com.omero.auth.entity.TenantAddon();
        row.setTenantId(tenantId);
        row.setAddonCode("whatsapp");
        row.setStatus("active");
        when(accounts.requireUser(userId)).thenReturn(user);
        when(plans.findByCodeIgnoreCase("whatsapp")).thenReturn(Optional.of(wa));
        when(tenants.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantAddons.findByTenantId(tenantId)).thenReturn(List.of(row));

        assertThrows(PlanNotAvailableException.class,
                () -> svc.subscriptionCheckoutUrl(userId, "whatsapp", List.of()));
    }
}
