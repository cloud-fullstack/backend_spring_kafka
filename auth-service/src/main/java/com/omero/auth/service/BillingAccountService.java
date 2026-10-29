package com.omero.auth.service;

import com.omero.auth.entity.BillingAccount;
import com.omero.auth.entity.StripeSubscription;
import com.omero.auth.entity.SubscriptionPlan;
import com.omero.auth.entity.Tenant;
import com.omero.auth.entity.TenantAddon;
import com.omero.auth.entity.User;
import com.omero.auth.repository.BillingAccountRepository;
import com.omero.auth.repository.StripeSubscriptionRepository;
import com.omero.auth.repository.SubscriptionPlanRepository;
import com.omero.auth.repository.TenantAddonRepository;
import com.omero.auth.repository.TenantRepository;
import com.omero.auth.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BillingAccountService {

    private static final int DEMO_DAILY_CONV = 20;

    private final BillingAccountRepository accounts;
    private final UserRepository users;
    private final TenantRepository tenants;
    private final SubscriptionPlanRepository plans;
    private final StripeSubscriptionRepository stripeSubscriptions;
    private final TenantAddonRepository tenantAddons;
    private final String lemonPortalUrl;
    private final int demoDays;

    public BillingAccountService(
            BillingAccountRepository accounts,
            UserRepository users,
            TenantRepository tenants,
            SubscriptionPlanRepository plans,
            StripeSubscriptionRepository stripeSubscriptions,
            TenantAddonRepository tenantAddons,
            @Value("${lemonsqueezy.customer-portal-url:}") String lemonPortalUrl,
            @Value("${billing.demo-days:3}") int demoDays) {
        this.accounts = accounts;
        this.users = users;
        this.tenants = tenants;
        this.plans = plans;
        this.stripeSubscriptions = stripeSubscriptions;
        this.tenantAddons = tenantAddons;
        this.lemonPortalUrl = lemonPortalUrl == null ? "" : lemonPortalUrl.trim();
        this.demoDays = Math.max(1, demoDays);
    }

    public User requireUser(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> new SecurityException("Invalid credentials"));
        if (!user.isActive() || user.getTenantId() == null) {
            throw new SecurityException("Invalid credentials");
        }
        return user;
    }

    @Transactional
    public BillingAccount ensure(UUID tenantId) {
        return accounts.findById(tenantId).orElseGet(() -> {
            BillingAccount a = new BillingAccount();
            a.setTenantId(tenantId);
            a.setSpendMonth(YearMonth.now(ZoneOffset.UTC).toString());
            return accounts.save(a);
        });
    }

    @Transactional
    public Map<String, Object> snapshot(UUID userId) {
        User user = requireUser(userId);
        BillingAccount a = ensure(user.getTenantId());
        rollMonth(a);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planId", user.getPlanId());
        m.put("email", user.getEmail());
        m.put("balanceCents", a.getBalanceCents());
        m.put("spendMonthCents", a.getSpendMonthCents());
        m.put("currency", "eur");
        m.put("exhausted", a.getBalanceCents() <= 0 && !"demo".equalsIgnoreCase(user.getPlanId()));
        boolean demo = "demo".equalsIgnoreCase(user.getPlanId());
        m.put("demoDailyLimit", DEMO_DAILY_CONV);
        if (demo) {
            Tenant t = tenants.findById(user.getTenantId()).orElse(null);
            OffsetDateTime created = t != null && t.getCreatedAt() != null ? t.getCreatedAt() : user.getCreatedAt();
            OffsetDateTime expires = created == null ? null : created.plusDays(demoDays);
            m.put("demoStartedAt", created);
            m.put("demoExpiresAt", expires);
            boolean expired = expires != null && OffsetDateTime.now(ZoneOffset.UTC).isAfter(expires);
            m.put("demoExpired", expired);
        } else {
            m.put("demoStartedAt", null);
            m.put("demoExpiresAt", null);
            m.put("demoExpired", false);
        }
        m.put("subscription", subscriptionSnapshot(user.getTenantId(), user.getPlanId()));
        return m;
    }

    private Map<String, Object> subscriptionSnapshot(UUID tenantId, String planId) {
        Map<String, Object> sub = new LinkedHashMap<>();
        sub.put("plan", planId);
        Tenant t = tenants.findById(tenantId).orElse(null);
        sub.put("status", t == null ? "" : t.getStatus());
        StripeSubscription live = null;
        for (StripeSubscription row : stripeSubscriptions.findByTenantId(tenantId)) {
            if (row.getPlanCode() == null || row.getPlanCode().isBlank()) {
                continue;
            }
            if (!"active".equalsIgnoreCase(row.getStatus())
                    && !"trialing".equalsIgnoreCase(row.getStatus())
                    && !"past_due".equalsIgnoreCase(row.getStatus())) {
                continue;
            }
            if (live == null || (row.getEventAt() != null && live.getEventAt() != null
                    && row.getEventAt().isAfter(live.getEventAt()))) {
                live = row;
            }
        }
        boolean canceling = live != null && live.isCancelAtPeriodEnd();
        sub.put("cancelAtPeriodEnd", canceling);
        OffsetDateTime ends = live == null ? null : (live.getCancelAt() != null ? live.getCancelAt() : live.getPeriodEnd());
        sub.put("endsAt", ends);
        java.util.List<String> addons = new java.util.ArrayList<>();
        for (TenantAddon a : tenantAddons.findByTenantId(tenantId)) {
            if ("active".equalsIgnoreCase(a.getStatus()) && a.getAddonCode() != null) {
                addons.add(a.getAddonCode());
            }
        }
        sub.put("addons", addons);
        boolean lemon = t != null
                && "active".equalsIgnoreCase(t.getStatus())
                && t.getLemonSubscriptionUpdatedAt() != null
                && !stripeSubscriptions.existsByTenantId(tenantId);
        sub.put("provider", lemon ? "lemon" : "stripe");
        if (lemon && !lemonPortalUrl.isBlank()) {
            sub.put("portalUrl", lemonPortalUrl);
        }
        return sub;
    }

    public Map<String, Object> usage(UUID userId) {
        User user = requireUser(userId);
        SubscriptionPlan plan = plans.findByCodeIgnoreCase(user.getPlanId()).orElse(null);
        int included = DEMO_DAILY_CONV;
        if (plan != null && plan.getFeatures() != null && plan.getFeatures().get("chat_conversations") instanceof Number n) {
            included = n.intValue();
        }
        OffsetDateTime resets = OffsetDateTime.now(ZoneOffset.UTC).plusMonths(1).withDayOfMonth(1)
                .withHour(0).withMinute(0).withSecond(0).withNano(0);
        Map<String, Object> conversations = new LinkedHashMap<>();
        conversations.put("used", 0);
        conversations.put("included", included);
        conversations.put("resetsAt", resets);
        conversations.put("forecastExhaustAt", null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("conversations", conversations);
        return m;
    }

    @Transactional
    public void addCredits(UUID tenantId, long cents) {
        ensure(tenantId);
        accounts.addBalance(tenantId, cents);
    }

    @Transactional
    public void setStripeCustomerIfAbsent(UUID tenantId, String customerId) {
        if (customerId == null || customerId.isBlank()) {
            return;
        }
        ensure(tenantId);
        accounts.setStripeCustomerIfAbsent(tenantId, customerId);
    }

    private void rollMonth(BillingAccount a) {
        String now = YearMonth.now(ZoneOffset.UTC).toString();
        if (now.equals(a.getSpendMonth())) {
            return;
        }
        a.setSpendMonth(now);
        a.setSpendMonthCents(0);
    }
}
