package com.omero.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omero.auth.entity.BillingAccount;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    @Mock CancellationNoticeService cancellationNotices;

    private StripeCheckoutService svc;
    private final UUID userId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        svc = new StripeCheckoutService(
                accounts, tenants, tenantAddons, plans, subscriptions, lemonCheckout, stripeApi,
                cancellationNotices, "txcd_10105002", "https://omero.dev");
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

    @Test
    void checkoutSessionSummaryReturnsPaidPlan() throws Exception {
        ObjectMapper om = new ObjectMapper();
        when(stripeApi.get("/v1/checkout/sessions/cs_test_abc123?expand[]=subscription"))
                .thenReturn(om.readTree("""
                        {
                          "id": "cs_test_abc123",
                          "status": "complete",
                          "payment_status": "paid",
                          "amount_total": 4900,
                          "currency": "eur",
                          "client_reference_id": "22222222-2222-2222-2222-222222222222",
                          "metadata": {
                            "user_id": "22222222-2222-2222-2222-222222222222",
                            "kind": "plan",
                            "plan_code": "silver"
                          },
                          "subscription": { "current_period_end": 1794240000 }
                        }
                        """));
        SubscriptionPlan silver = new SubscriptionPlan();
        silver.setCode("silver");
        silver.setName("Silver");
        when(plans.findByCodeIgnoreCase("silver")).thenReturn(Optional.of(silver));

        Map<String, Object> out = svc.checkoutSessionSummary(userId, "cs_test_abc123");
        assertEquals(true, out.get("paid"));
        assertEquals("plan", out.get("kind"));
        assertEquals("silver", out.get("planCode"));
        assertEquals("Silver", out.get("planName"));
        assertEquals(4900L, out.get("amountCents"));
        assertTrue(String.valueOf(out.get("renewsAt")).startsWith("2026"));
    }

    @Test
    void checkoutSessionSummaryRejectsForeignSession() throws Exception {
        ObjectMapper om = new ObjectMapper();
        when(stripeApi.get("/v1/checkout/sessions/cs_test_other?expand[]=subscription"))
                .thenReturn(om.readTree("""
                        {
                          "metadata": { "user_id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa" },
                          "client_reference_id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
                        }
                        """));
        assertThrows(IllegalArgumentException.class,
                () -> svc.checkoutSessionSummary(userId, "cs_test_other"));
    }

    @Test
    void checkoutSessionSummaryRejectsInvalidId() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.checkoutSessionSummary(userId, "../evil"));
        verify(stripeApi, never()).get(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void icalCheckoutCreatesOneShotPayment() throws Exception {
        User user = new User();
        user.setId(userId);
        user.setTenantId(tenantId);
        user.setEmail("a@b.c");
        BillingAccount account = new BillingAccount();
        account.setTenantId(tenantId);
        when(accounts.requireUser(userId)).thenReturn(user);
        when(tenantAddons.existsByTenantIdAndAddonCodeAndStatusIgnoreCase(
                tenantId, "ical_integration", "active")).thenReturn(false);
        when(accounts.ensure(tenantId)).thenReturn(account);
        ObjectMapper om = new ObjectMapper();
        when(stripeApi.post(eq("/v1/checkout/sessions"), any(), any()))
                .thenReturn(om.readTree("{\"url\":\"https://checkout.stripe.com/ical\"}"));

        assertEquals("https://checkout.stripe.com/ical", svc.icalCheckoutUrl(userId));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MultiValueMap<String, String>> form = ArgumentCaptor.forClass(MultiValueMap.class);
        verify(stripeApi).post(eq("/v1/checkout/sessions"), form.capture(), any());
        assertEquals("payment", form.getValue().getFirst("mode"));
        assertEquals("ical", form.getValue().getFirst("metadata[kind]"));
        assertEquals("7900", form.getValue().getFirst("line_items[0][price_data][unit_amount]"));
        assertEquals("true", form.getValue().getFirst("managed_payments[enabled]"));
    }

    @Test
    void vehicleApiComingSoonRejected() {
        SubscriptionPlan api = new SubscriptionPlan();
        api.setCode("vehicle_api");
        api.setPrice(new java.math.BigDecimal("99.00"));
        api.setFeatures(Map.of("kind", "addon", "status", "coming_soon", "billing", "one_off"));
        when(plans.findByCodeIgnoreCase("vehicle_api")).thenReturn(Optional.of(api));
        assertThrows(PlanNotAvailableException.class, () -> svc.oneOffCheckoutUrl(userId, "vehicle_api"));
    }

    @Test
    void jollyCheckoutUsesRecurringPriceData() throws Exception {
        User user = new User();
        user.setId(userId);
        user.setTenantId(tenantId);
        user.setEmail("a@b.c");
        BillingAccount account = new BillingAccount();
        account.setTenantId(tenantId);
        SubscriptionPlan jolly = new SubscriptionPlan();
        jolly.setCode("jolly");
        jolly.setPrice(new java.math.BigDecimal("20.00"));
        jolly.setFeatures(Map.of("kind", "addon", "status", "coming_soon", "billing", "recurring"));
        when(accounts.requireUser(userId)).thenReturn(user);
        when(tenantAddons.existsByTenantIdAndAddonCodeAndStatusIgnoreCase(tenantId, "jolly", "active"))
                .thenReturn(false);
        when(accounts.ensure(tenantId)).thenReturn(account);
        when(plans.findByCodeIgnoreCase("jolly")).thenReturn(Optional.of(jolly));
        ObjectMapper om = new ObjectMapper();
        when(stripeApi.post(eq("/v1/checkout/sessions"), any(), any()))
                .thenReturn(om.readTree("{\"url\":\"https://checkout.stripe.com/jolly\"}"));

        assertEquals("https://checkout.stripe.com/jolly", svc.jollyCheckoutUrl(userId, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MultiValueMap<String, String>> form = ArgumentCaptor.forClass(MultiValueMap.class);
        verify(stripeApi).post(eq("/v1/checkout/sessions"), form.capture(), any());
        assertEquals("subscription", form.getValue().getFirst("mode"));
        assertEquals("jolly", form.getValue().getFirst("metadata[kind]"));
        assertEquals("month", form.getValue().getFirst("line_items[0][price_data][recurring][interval]"));
        assertEquals("2000", form.getValue().getFirst("line_items[0][price_data][unit_amount]"));
        org.junit.jupiter.api.Assertions.assertNull(
                form.getValue().getFirst("subscription_data[add_invoice_items][0][price_data][unit_amount]"));
    }

    @Test
    void jollyCheckoutWithNumberRequestAddsSetup() throws Exception {
        User user = new User();
        user.setId(userId);
        user.setTenantId(tenantId);
        user.setEmail("a@b.c");
        BillingAccount account = new BillingAccount();
        when(accounts.requireUser(userId)).thenReturn(user);
        when(tenantAddons.existsByTenantIdAndAddonCodeAndStatusIgnoreCase(tenantId, "jolly", "active"))
                .thenReturn(false);
        when(accounts.ensure(tenantId)).thenReturn(account);
        when(plans.findByCodeIgnoreCase("jolly")).thenReturn(Optional.empty());
        when(plans.findByCodeIgnoreCase("whatsapp_number_setup")).thenReturn(Optional.empty());
        ObjectMapper om = new ObjectMapper();
        when(stripeApi.post(eq("/v1/checkout/sessions"), any(), any()))
                .thenReturn(om.readTree("{\"url\":\"https://checkout.stripe.com/jolly-setup\"}"));
        UUID req = UUID.fromString("33333333-3333-3333-3333-333333333333");

        svc.jollyCheckoutUrl(userId, req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MultiValueMap<String, String>> form = ArgumentCaptor.forClass(MultiValueMap.class);
        verify(stripeApi).post(eq("/v1/checkout/sessions"), form.capture(), any());
        assertEquals(req.toString(), form.getValue().getFirst("metadata[number_request_id]"));
        assertEquals("jolly_with_setup", form.getValue().getFirst("metadata[kind]"));
        assertEquals("jolly_with_setup", form.getValue().getFirst("subscription_data[metadata][kind]"));
        assertEquals("4900", form.getValue().getFirst(
                "subscription_data[add_invoice_items][0][price_data][unit_amount]"));
        org.junit.jupiter.api.Assertions.assertNull(form.getValue().getFirst("line_items[1][price_data][unit_amount]"));
    }
}
