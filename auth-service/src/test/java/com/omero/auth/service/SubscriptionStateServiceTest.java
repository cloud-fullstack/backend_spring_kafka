package com.omero.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omero.auth.entity.StripeSubscription;
import com.omero.auth.entity.SubscriptionPlan;
import com.omero.auth.entity.Tenant;
import com.omero.auth.entity.User;
import com.omero.auth.repository.BillingCommissionRepository;
import com.omero.auth.repository.StripeInvoicePaymentRepository;
import com.omero.auth.repository.StripeSubscriptionRepository;
import com.omero.auth.repository.SubscriptionPlanRepository;
import com.omero.auth.repository.TenantRepository;
import com.omero.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionStateServiceTest {

    @Mock UserRepository users;
    @Mock TenantRepository tenants;
    @Mock SubscriptionPlanRepository plans;
    @Mock BillingCommissionRepository commissions;
    @Mock StripeInvoicePaymentRepository invoicePayments;
    @Mock StripeSubscriptionRepository stripeSubs;
    @Mock PlanAccessService planAccess;
    @Mock ChannelEntitlementClient entitlements;
    @Mock StripeApi stripeApi;

    private final ObjectMapper mapper = new ObjectMapper();
    private SubscriptionStateService svc;
    private final List<StripeSubscription> store = new ArrayList<>();
    private final UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID userId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        svc = new SubscriptionStateService(
                users, tenants, plans, commissions, invoicePayments, stripeSubs, planAccess, entitlements, stripeApi);
        lenient().when(stripeApi.get(any())).thenReturn(null);
        lenient().when(stripeSubs.findById(any())).thenAnswer(inv -> store.stream()
                .filter(s -> s.getSubscriptionId().equals(inv.getArgument(0)))
                .findFirst());
        lenient().when(stripeSubs.save(any())).thenAnswer(inv -> {
            StripeSubscription row = inv.getArgument(0);
            store.removeIf(s -> s.getSubscriptionId().equals(row.getSubscriptionId()));
            store.add(row);
            return row;
        });
        lenient().when(stripeSubs.findByTenantId(tenantId)).thenAnswer(inv -> List.copyOf(store));
    }

    @Test
    void portalPlanChangeUsesPriceIdNotMetadata() throws Exception {
        User user = user("bronze");
        Tenant tenant = tenant("active");
        stubUser(user, tenant);
        when(plans.findByStripePriceId("price_bronze")).thenReturn(Optional.of(plan("bronze", false)));
        when(plans.findByStripePriceId("price_silver")).thenReturn(Optional.of(plan("silver", false)));

        svc.applyStripeObject(mapper.readTree(subJson(
                "sub_1", "active", "bronze", "price_bronze", 1_700_000_000L, null)),
                OffsetDateTime.parse("2026-10-26T10:00:00Z"), false);
        svc.applyStripeObject(mapper.readTree(subJson(
                "sub_1", "active", "bronze", "price_silver", 1_700_000_100L, null)),
                OffsetDateTime.parse("2026-10-26T11:00:00Z"), false);

        assertEquals("silver", user.getPlanId());
        assertEquals("active", tenant.getStatus());
        verify(entitlements, atLeastOnce()).replaceAddons(eq(tenantId), eq(Set.of()), eq("stripe"));
        verify(entitlements, atLeastOnce()).syncAfterCommit(tenantId, "silver", "active");
    }

    @Test
    void pastDueKeepsTenantActive() throws Exception {
        User user = user("bronze");
        Tenant tenant = tenant("active");
        stubUser(user, tenant);
        when(plans.findByStripePriceId("price_bronze")).thenReturn(Optional.of(plan("bronze", false)));

        svc.applyStripeObject(mapper.readTree(subJson(
                "sub_1", "active", "bronze", "price_bronze", 1_700_000_000L, null)),
                OffsetDateTime.parse("2026-10-26T10:00:00Z"), false);
        svc.applyStripeObject(mapper.readTree(subJson(
                "sub_1", "past_due", "bronze", "price_bronze", 1_700_000_200L, null)),
                OffsetDateTime.parse("2026-10-26T12:00:00Z"), false);

        assertEquals("active", tenant.getStatus());
        assertEquals("bronze", user.getPlanId());
        verify(entitlements, atLeastOnce()).syncAfterCommit(tenantId, "bronze", "active");
    }

    @Test
    void staleEventOnSameSubscriptionIsIgnored() throws Exception {
        User user = user("bronze");
        Tenant tenant = tenant("active");
        stubUser(user, tenant);
        when(plans.findByStripePriceId("price_silver")).thenReturn(Optional.of(plan("silver", false)));

        svc.applyStripeObject(mapper.readTree(subJson(
                "sub_1", "active", "bronze", "price_silver", 1L, null)),
                OffsetDateTime.parse("2026-10-26T12:00:00Z"), false);
        svc.applyStripeObject(mapper.readTree(subJson(
                "sub_1", "active", "bronze", "price_bronze", 1L, null)),
                OffsetDateTime.parse("2026-10-26T11:00:00Z"), false);

        assertEquals("silver", user.getPlanId());
    }

    @Test
    void staleWebhookIgnoredWhenLiveGetFails() throws Exception {
        User user = user("bronze");
        Tenant tenant = tenant("active");
        stubUser(user, tenant);
        when(plans.findByStripePriceId("price_silver")).thenReturn(Optional.of(plan("silver", false)));
        when(stripeApi.get(any())).thenThrow(new IllegalStateException("stripe down"));

        svc.applyStripeObject(mapper.readTree(subJson(
                "sub_1", "active", "bronze", "price_silver", 1L, null)),
                OffsetDateTime.parse("2026-10-26T12:00:00Z"), false);
        svc.applyStripeObject(mapper.readTree(subJson(
                "sub_1", "active", "bronze", "price_bronze", 1L, null)),
                OffsetDateTime.parse("2026-10-26T11:00:00Z"), false);

        assertEquals("silver", user.getPlanId());
    }

    @Test
    void cancelAtPeriodEndKeepsTenantActiveAndStoresEndsAt() throws Exception {
        User user = user("silver");
        Tenant tenant = tenant("active");
        stubUser(user, tenant);
        when(plans.findByStripePriceId("price_silver")).thenReturn(Optional.of(plan("silver", false)));
        String json = subJson("sub_1", "active", "silver", "price_silver", 1_800_000_000L, null)
                .replace("\"status\":\"active\"", "\"status\":\"active\",\"cancel_at_period_end\":true");

        svc.applyStripeObject(mapper.readTree(json), OffsetDateTime.parse("2026-10-26T10:00:00Z"), false);

        assertEquals("active", tenant.getStatus());
        assertEquals(true, store.get(0).isCancelAtPeriodEnd());
        assertEquals(null, tenant.getSuspendedAt());
    }

    @Test
    void lemonTenantBuyingStripeAddonIsNotSuspended() throws Exception {
        User user = user("gold");
        Tenant tenant = tenant("active");
        stubUser(user, tenant);
        when(plans.findByStripePriceId("price_wa")).thenReturn(Optional.of(plan("whatsapp", true)));

        svc.applyStripeObject(mapper.readTree(subJson(
                "sub_addon", "active", "whatsapp", "price_wa", 1_700_000_000L, null)),
                OffsetDateTime.parse("2026-10-26T10:00:00Z"), false);

        assertEquals("active", tenant.getStatus());
        assertEquals("gold", user.getPlanId());
        verify(entitlements).replaceAddons(eq(tenantId), eq(Set.of("whatsapp")), eq("stripe"));
    }

    @Test
    void suspendedTenantCancelsAddonOnlyStripeSubscription() throws Exception {
        User user = user("bronze");
        Tenant tenant = tenant("active");
        stubUser(user, tenant);
        when(plans.findByStripePriceId("price_bronze")).thenReturn(Optional.of(plan("bronze", false)));
        StripeSubscription addonOnly = new StripeSubscription();
        addonOnly.setSubscriptionId("sub_wa");
        addonOnly.setTenantId(tenantId);
        addonOnly.setStatus("active");
        addonOnly.setAddonCodes("whatsapp");
        store.add(addonOnly);

        svc.applyStripeObject(mapper.readTree(subJson(
                "sub_plan", "active", "bronze", "price_bronze", 1L, null)),
                OffsetDateTime.parse("2026-10-26T10:00:00Z"), false);
        svc.applyStripeObject(mapper.readTree(subJson(
                "sub_plan", "canceled", "bronze", "price_bronze", 1L, null)),
                OffsetDateTime.parse("2026-10-26T11:00:00Z"), true);

        verify(stripeApi).post(eq("/v1/subscriptions/sub_wa"), any(), eq("cancel-addons-end:sub_wa"));
        assertEquals("suspended", tenant.getStatus());
    }

    @Test
    void incompleteThenActiveSameSecondUsesLiveGet() throws Exception {
        User user = user("demo");
        Tenant tenant = tenant("demo");
        stubUser(user, tenant);
        when(plans.findByStripePriceId("price_bronze")).thenReturn(Optional.of(plan("bronze", false)));
        when(stripeApi.get(any())).thenReturn(mapper.readTree(subJson(
                "sub_1", "active", "bronze", "price_bronze", 1_700_000_000L, null)));

        OffsetDateTime same = OffsetDateTime.parse("2026-10-26T10:00:00Z");
        svc.applyStripeObject(mapper.readTree(subJson(
                "sub_1", "incomplete", "bronze", "price_bronze", 1_700_000_000L, null)), same, false);

        assertEquals("active", tenant.getStatus());
        assertEquals("bronze", user.getPlanId());
        assertEquals(null, tenant.getSuspendedAt());
    }

    @Test
    void addonRemovedFromItemsIsDeactivated() throws Exception {
        User user = user("bronze");
        Tenant tenant = tenant("active");
        stubUser(user, tenant);
        when(plans.findByStripePriceId("price_bronze")).thenReturn(Optional.of(plan("bronze", false)));
        when(plans.findByStripePriceId("price_wa")).thenReturn(Optional.of(plan("whatsapp", true)));

        svc.applyStripeObject(mapper.readTree(subJson(
                "sub_1", "active", "bronze", "price_bronze", 1L, "price_wa")),
                OffsetDateTime.parse("2026-10-26T10:00:00Z"), false);
        svc.applyStripeObject(mapper.readTree(subJson(
                "sub_1", "active", "bronze", "price_bronze", 1L, null)),
                OffsetDateTime.parse("2026-10-26T11:00:00Z"), false);

        verify(entitlements).replaceAddons(eq(tenantId), eq(Set.of("whatsapp")), eq("stripe"));
        verify(entitlements).replaceAddons(eq(tenantId), eq(Set.of()), eq("stripe"));
        assertEquals("active", tenant.getStatus());
    }

    private void stubUser(User user, Tenant tenant) {
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(tenants.findById(tenantId)).thenReturn(Optional.of(tenant));
    }

    private User user(String plan) {
        User user = new User();
        user.setId(userId);
        user.setTenantId(tenantId);
        user.setPlanId(plan);
        return user;
    }

    private Tenant tenant(String status) {
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setStatus(status);
        return tenant;
    }

    private static SubscriptionPlan plan(String code, boolean addon) {
        SubscriptionPlan p = new SubscriptionPlan();
        p.setCode(code);
        p.setStripePriceId("price_" + code);
        p.setFeatures(addon
                ? Map.of("kind", "addon", "status", "available")
                : Map.of("status", "available"));
        return p;
    }

    private String subJson(String id, String status, String metaPlan, String planPrice, long periodEnd, String addonPrice) {
        String addonItem = addonPrice == null ? "" : """
                ,{"price":{"id":"%s"},"current_period_end":%d}
                """.formatted(addonPrice, periodEnd);
        return """
                {"id":"%s","status":"%s","metadata":{"user_id":"%s","tenant_id":"%s","plan_code":"%s"},
                 "items":{"data":[{"price":{"id":"%s"},"current_period_end":%d}%s]}}
                """.formatted(id, status, userId, tenantId, metaPlan, planPrice, periodEnd, addonItem);
    }
}
