package com.omero.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.omero.auth.entity.BillingCommission;
import com.omero.auth.entity.StripeSubscription;
import com.omero.auth.entity.SubscriptionPlan;
import com.omero.auth.entity.Tenant;
import com.omero.auth.entity.User;
import com.omero.auth.entity.StripeInvoicePayment;
import com.omero.auth.repository.BillingCommissionRepository;
import com.omero.auth.repository.StripeInvoicePaymentRepository;
import com.omero.auth.repository.StripeSubscriptionRepository;
import com.omero.auth.repository.SubscriptionPlanRepository;
import com.omero.auth.repository.TenantRepository;
import com.omero.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.LinkedMultiValueMap;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class SubscriptionStateService {

    public enum Provider { LEMON, STRIPE }

    private static final Logger log = LoggerFactory.getLogger(SubscriptionStateService.class);
    private static final Set<String> LEMON_ACTIVE = Set.of("active", "on_trial");
    private static final Set<String> LEMON_SUSPENDED = Set.of("expired", "unpaid", "paused");
    private static final Set<String> STRIPE_LIVE = Set.of("active", "trialing", "past_due");
    private static final Set<String> STRIPE_DEAD = Set.of("unpaid", "canceled", "cancelled", "incomplete_expired");
    private static final Set<String> STRIPE_NEUTRAL = Set.of("incomplete", "paused");

    private record Lines(String plan, Set<String> addons) {}

    private final UserRepository users;
    private final TenantRepository tenants;
    private final SubscriptionPlanRepository plans;
    private final BillingCommissionRepository commissions;
    private final StripeInvoicePaymentRepository invoicePayments;
    private final StripeSubscriptionRepository stripeSubscriptions;
    private final PlanAccessService planAccess;
    private final ChannelEntitlementClient channelEntitlements;
    private final StripeApi stripeApi;

    public SubscriptionStateService(
            UserRepository users,
            TenantRepository tenants,
            SubscriptionPlanRepository plans,
            BillingCommissionRepository commissions,
            StripeInvoicePaymentRepository invoicePayments,
            StripeSubscriptionRepository stripeSubscriptions,
            PlanAccessService planAccess,
            ChannelEntitlementClient channelEntitlements,
            StripeApi stripeApi) {
        this.users = users;
        this.tenants = tenants;
        this.plans = plans;
        this.commissions = commissions;
        this.invoicePayments = invoicePayments;
        this.stripeSubscriptions = stripeSubscriptions;
        this.planAccess = planAccess;
        this.channelEntitlements = channelEntitlements;
        this.stripeApi = stripeApi;
    }

    public void apply(
            User user,
            String planCode,
            String status,
            String eventName,
            OffsetDateTime eventAt,
            OffsetDateTime endsAt,
            Provider provider,
            boolean subscriptionObject) {
        if (user == null) {
            return;
        }
        if (provider == Provider.LEMON && subscriptionObject && isStaleLemon(user, eventAt)) {
            return;
        }
        String name = eventName == null ? "" : eventName.toLowerCase(Locale.ROOT);
        String st = status == null ? "" : status.toLowerCase(Locale.ROOT);
        boolean addon = isAddon(planCode);
        boolean activate = isActivate(st, name, provider);
        boolean suspend = isSuspend(st, name, provider);
        boolean cancelPending = "cancelled".equals(st) || "canceled".equals(st) || "deleted".equals(name);

        if (addon) {
            if (user.getTenantId() != null) {
                if (activate) {
                    channelEntitlements.activateAddon(
                            user.getTenantId(), planCode, provider == Provider.LEMON ? "lemon" : "stripe");
                } else if (suspend || cancelPending) {
                    channelEntitlements.deactivateAddon(
                            user.getTenantId(), planCode, provider == Provider.LEMON ? "lemon" : "stripe");
                }
            }
        } else if (planCode != null && !planCode.isBlank() && activate) {
            user.setPlanId(planCode);
        }

        if (!addon && user.getTenantId() != null) {
            Tenant tenant = tenants.findById(user.getTenantId()).orElse(null);
            if (tenant != null) {
                String previous = tenant.getStatus();
                if (activate) {
                    tenant.setStatus("active");
                    tenant.setSuspendedAt(null);
                } else if (suspend) {
                    tenant.setStatus("suspended");
                    if (tenant.getSuspendedAt() == null) {
                        tenant.setSuspendedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    }
                } else if (cancelPending) {
                    if (endsAt == null || !endsAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
                        tenant.setStatus("suspended");
                        if (tenant.getSuspendedAt() == null) {
                            tenant.setSuspendedAt(OffsetDateTime.now(ZoneOffset.UTC));
                        }
                    }
                }
                if (provider == Provider.LEMON) {
                    touchLemon(tenant, eventAt, subscriptionObject);
                }
                tenants.save(tenant);
                cancelAddonOnlyIfSuspended(tenant, previous);
            }
        } else if (subscriptionObject && user.getTenantId() != null && provider == Provider.LEMON) {
            Tenant tenant = tenants.findById(user.getTenantId()).orElse(null);
            if (tenant != null) {
                touchLemon(tenant, eventAt, true);
                tenants.save(tenant);
            }
        }
        users.save(user);
        syncUser(user);
    }

    public void applyStripeObject(JsonNode obj, OffsetDateTime eventAt, boolean deleted) {
        String subId = text(obj.path("id"));
        if (subId.isBlank()) {
            return;
        }
        JsonNode live = null;
        if (!deleted) {
            try {
                live = stripeApi.get("/v1/subscriptions/" + subId);
            } catch (RuntimeException e) {
                log.warn("Stripe GET subscription {} failed, using webhook payload", subId);
            }
        }
        boolean fromLive = live != null && !text(live.path("id")).isBlank();
        if (fromLive) {
            obj = live;
            if ("canceled".equalsIgnoreCase(text(obj.path("status")))) {
                deleted = true;
            }
        }
        JsonNode meta = obj.path("metadata");
        User user = resolveUser(meta);
        if (user == null) {
            log.warn("Stripe subscription without matching user");
            return;
        }
        if (user.getTenantId() == null) {
            return;
        }
        OffsetDateTime at = eventAt != null ? eventAt : OffsetDateTime.now(ZoneOffset.UTC);
        StripeSubscription row = stripeSubscriptions.findById(subId).orElse(null);
        if (!fromLive && row != null && row.getEventAt() != null && at.isBefore(row.getEventAt())) {
            return;
        }
        if (row == null) {
            row = new StripeSubscription();
            row.setSubscriptionId(subId);
            row.setTenantId(user.getTenantId());
        }
        String status = deleted ? "canceled" : text(obj.path("status")).toLowerCase(Locale.ROOT);
        Lines lines = fromItems(obj);
        if (lines.plan() == null) {
            String fallback = text(meta.path("plan_code"));
            if (!fallback.isBlank() && !isAddon(fallback)) {
                log.warn("Stripe subscription {} using metadata.plan_code={}", subId, fallback);
                lines = new Lines(fallback, lines.addons());
            }
        }
        if (lines.plan() == null && lines.addons().isEmpty() && row.getPlanCode() != null) {
            lines = new Lines(row.getPlanCode(), splitAddons(row.getAddonCodes()));
        }
        row.setStatus(status);
        row.setPlanCode(lines.plan());
        row.setAddonCodes(joinAddons(lines.addons()));
        row.setEventAt(at);
        row.setCancelAtPeriodEnd(obj.path("cancel_at_period_end").asBoolean(false));
        row.setCancelAt(unix(obj.path("cancel_at").asLong(0)));
        row.setPeriodEnd(periodEnd(obj));
        row.setCanceledAt(unix(obj.path("canceled_at").asLong(0)));
        stripeSubscriptions.save(row);
        recomputeFromStripe(user);
    }

    public void accrueStripeInvoice(JsonNode invoice) {
        JsonNode meta = invoiceMetadata(invoice);
        if ("credits".equalsIgnoreCase(text(meta.path("kind")))) {
            return;
        }
        String invoiceId = text(invoice.path("id"));
        if (invoiceId.isBlank()) {
            return;
        }
        User user = resolveUser(meta);
        if (user == null) {
            user = resolveUserFromSubscription(invoice);
        }
        if (user == null) {
            return;
        }
        Lines lines = fromInvoiceLines(invoice);
        String plan = lines.plan();
        if (plan == null || plan.isBlank() || isAddon(plan)) {
            plan = user.getPlanId();
        }
        long paid = invoice.path("amount_paid").asLong(0);
        long tax = invoiceTax(invoice);
        BigDecimal net = BigDecimal.valueOf(Math.max(0, paid - tax)).movePointLeft(2);
        String pi = stripeApi.firstPaidPaymentIntent(invoiceId);
        rememberInvoicePayment(user.getTenantId(), invoiceId, pi);
        accrue(user, plan, net, null, invoiceId, pi);
    }

    public void accrue(User user, String planCode, BigDecimal net, String lemonOrderId, String stripeInvoiceId) {
        accrue(user, planCode, net, lemonOrderId, stripeInvoiceId, null);
    }

    public void accrue(
            User user,
            String planCode,
            BigDecimal net,
            String lemonOrderId,
            String stripeInvoiceId,
            String stripePaymentIntentId) {
        if (user == null || user.getTenantId() == null) {
            return;
        }
        if (lemonOrderId != null && !lemonOrderId.isBlank() && commissions.existsByLemonOrderId(lemonOrderId)) {
            return;
        }
        if (stripeInvoiceId != null && !stripeInvoiceId.isBlank() && commissions.existsByStripeInvoiceId(stripeInvoiceId)) {
            return;
        }
        Tenant tenant = tenants.findById(user.getTenantId()).orElse(null);
        if (tenant == null || tenant.getResellerId() == null) {
            return;
        }
        SubscriptionPlan plan = planCode == null ? null : plans.findByCodeIgnoreCase(planCode).orElse(null);
        BigDecimal percent = plan != null && plan.getResellerCommissionPercent() != null
                ? plan.getResellerCommissionPercent()
                : BigDecimal.ZERO;
        if (percent.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (net == null || net.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal amount = net.multiply(percent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BillingCommission row = new BillingCommission();
        row.setResellerId(tenant.getResellerId());
        row.setTenantId(tenant.getId());
        row.setUserId(user.getId());
        row.setPlanCode(planCode);
        row.setAmount(amount);
        row.setCommissionPercent(percent);
        row.setLemonOrderId(blankToNull(lemonOrderId));
        row.setStripeInvoiceId(blankToNull(stripeInvoiceId));
        row.setStripePaymentIntentId(blankToNull(stripePaymentIntentId));
        row.setReversedAmount(BigDecimal.ZERO);
        row.setStatus("pending");
        commissions.save(row);
    }

    public void reverseCommission(String paymentIntentId, long amountRefunded, long chargeAmount) {
        if (paymentIntentId == null || paymentIntentId.isBlank() || amountRefunded <= 0 || chargeAmount <= 0) {
            return;
        }
        BillingCommission commission = commissions.findByStripePaymentIntentId(paymentIntentId).orElse(null);
        if (commission == null) {
            return;
        }
        BigDecimal share = commission.getAmount()
                .multiply(BigDecimal.valueOf(amountRefunded))
                .divide(BigDecimal.valueOf(chargeAmount), 2, RoundingMode.HALF_UP);
        BigDecimal reversed = share.min(commission.getAmount());
        commission.setReversedAmount(reversed);
        if (reversed.compareTo(commission.getAmount()) >= 0) {
            commission.setStatus("reversed");
        }
        commissions.save(commission);
    }

    public Optional<StripeSubscription> livePlanSubscription(UUID tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        StripeSubscription best = null;
        for (StripeSubscription row : stripeSubscriptions.findByTenantId(tenantId)) {
            if (!STRIPE_LIVE.contains(norm(row.getStatus()))) {
                continue;
            }
            if (row.getPlanCode() == null || row.getPlanCode().isBlank()) {
                continue;
            }
            if (best == null || compareLivePlans(row, best) > 0) {
                best = row;
            }
        }
        return Optional.ofNullable(best);
    }

    public void cancelAddonOnlyStripeSubscriptions(UUID tenantId) {
        if (tenantId == null) {
            return;
        }
        for (StripeSubscription row : stripeSubscriptions.findByTenantId(tenantId)) {
            if (!STRIPE_LIVE.contains(norm(row.getStatus()))) {
                continue;
            }
            if (row.getPlanCode() != null && !row.getPlanCode().isBlank()) {
                continue;
            }
            try {
                LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                form.add("cancel_at_period_end", "true");
                stripeApi.post(
                        "/v1/subscriptions/" + row.getSubscriptionId(),
                        form,
                        "cancel-addons-end:" + row.getSubscriptionId());
            } catch (RuntimeException e) {
                log.warn("addon-only Stripe cancel-at-period-end failed sub={}", row.getSubscriptionId());
            }
        }
    }

    public List<StripeSubscription> liveAddonOnlySubscriptions(UUID tenantId) {
        ArrayList<StripeSubscription> out = new ArrayList<>();
        if (tenantId == null) {
            return out;
        }
        for (StripeSubscription row : stripeSubscriptions.findByTenantId(tenantId)) {
            if (!STRIPE_LIVE.contains(norm(row.getStatus()))) {
                continue;
            }
            if (row.getPlanCode() != null && !row.getPlanCode().isBlank()) {
                continue;
            }
            out.add(row);
        }
        return out;
    }

    public boolean isAddon(String planCode) {
        if (planCode == null || planCode.isBlank()) {
            return false;
        }
        return plans.findByCodeIgnoreCase(planCode).map(SubscriptionPlan::isAddon).orElse(false);
    }

    Lines fromItems(JsonNode sub) {
        return fromPriceNodes(sub.path("items").path("data"));
    }

    private void recomputeFromStripe(User user) {
        UUID tenantId = user.getTenantId();
        Tenant tenant = tenants.findById(tenantId).orElse(null);
        if (tenant == null) {
            return;
        }
        String previous = tenant.getStatus();
        StripeSubscription chosen = null;
        int livePlanCount = 0;
        Set<String> addons = new LinkedHashSet<>();
        boolean planLive = false;
        boolean anyStripePlan = false;
        for (StripeSubscription row : stripeSubscriptions.findByTenantId(tenantId)) {
            String st = norm(row.getStatus());
            boolean hasPlan = row.getPlanCode() != null && !row.getPlanCode().isBlank();
            if (hasPlan && !STRIPE_NEUTRAL.contains(st)) {
                anyStripePlan = true;
            }
            if (!STRIPE_LIVE.contains(st)) {
                continue;
            }
            addons.addAll(splitAddons(row.getAddonCodes()));
            if (hasPlan) {
                planLive = true;
                livePlanCount++;
                if (chosen == null || compareLivePlans(row, chosen) > 0) {
                    chosen = row;
                }
            }
        }
        if (livePlanCount > 1) {
            log.warn("tenant {} has {} live Stripe plan subscriptions", tenantId, livePlanCount);
        }
        if (chosen != null) {
            user.setPlanId(chosen.getPlanCode());
        }
        if (anyStripePlan) {
            String next = planLive ? "active" : "suspended";
            if ("active".equalsIgnoreCase(tenant.getStatus()) && "suspended".equals(next)) {
                tenant.setSuspendedAt(OffsetDateTime.now(ZoneOffset.UTC));
            } else if ("active".equals(next)) {
                tenant.setSuspendedAt(null);
            }
            tenant.setStatus(next);
        }
        tenants.save(tenant);
        cancelAddonOnlyIfSuspended(tenant, previous);
        users.save(user);
        channelEntitlements.replaceAddons(tenantId, addons, "stripe");
        channelEntitlements.syncAfterCommit(tenantId, user.getPlanId(), tenant.getStatus());
        planAccess.evict(user.getId());
    }

    private static int compareLivePlans(StripeSubscription a, StripeSubscription b) {
        int rank = Integer.compare(planRank(a.getPlanCode()), planRank(b.getPlanCode()));
        if (rank != 0) {
            return rank;
        }
        OffsetDateTime ae = a.getEventAt();
        OffsetDateTime be = b.getEventAt();
        if (ae == null && be == null) {
            return 0;
        }
        if (ae == null) {
            return -1;
        }
        if (be == null) {
            return 1;
        }
        return ae.compareTo(be);
    }

    private static int planRank(String code) {
        if (code == null) {
            return 0;
        }
        return switch (code.toLowerCase(Locale.ROOT)) {
            case "platinum" -> 4;
            case "gold" -> 3;
            case "silver" -> 2;
            case "bronze" -> 1;
            default -> 0;
        };
    }

    private Lines fromPriceNodes(JsonNode items) {
        String plan = null;
        Set<String> addons = new LinkedHashSet<>();
        if (items == null || !items.isArray()) {
            return new Lines(null, addons);
        }
        for (JsonNode it : items) {
            String priceId = priceId(it);
            if (priceId.isBlank()) {
                continue;
            }
            Optional<SubscriptionPlan> p = plans.findByStripePriceId(priceId);
            if (p.isEmpty()) {
                continue;
            }
            if (p.get().isAddon()) {
                addons.add(p.get().getCode());
            } else {
                plan = p.get().getCode();
            }
        }
        return new Lines(plan, addons);
    }

    private Lines fromInvoiceLines(JsonNode invoice) {
        JsonNode lines = invoice.path("lines").path("data");
        return fromPriceNodes(lines);
    }

    private static long invoiceTax(JsonNode invoice) {
        JsonNode tax = invoice.path("tax");
        if (tax.isNumber()) {
            return Math.max(0, tax.asLong(0));
        }
        long sum = 0;
        boolean found = false;
        for (String field : new String[] {"total_taxes", "total_tax_amounts"}) {
            JsonNode arr = invoice.path(field);
            if (!arr.isArray() || arr.isEmpty()) {
                continue;
            }
            found = true;
            for (JsonNode t : arr) {
                sum += t.path("amount").asLong(0);
            }
        }
        return found ? Math.max(0, sum) : 0;
    }

    private static String priceId(JsonNode item) {
        JsonNode price = item.path("price");
        if (price.isTextual()) {
            return price.asText("");
        }
        String id = text(price.path("id"));
        if (!id.isBlank()) {
            return id;
        }
        return text(item.path("pricing").path("price_details").path("price"));
    }

    public static OffsetDateTime periodEnd(JsonNode sub) {
        long end = 0;
        JsonNode items = sub.path("items").path("data");
        if (items.isArray()) {
            for (JsonNode it : items) {
                end = Math.max(end, it.path("current_period_end").asLong(0));
            }
        }
        if (end <= 0) {
            end = sub.path("current_period_end").asLong(0);
        }
        return unix(end);
    }

    private void cancelAddonOnlyIfSuspended(Tenant tenant, String previousStatus) {
        if (tenant == null || tenant.getId() == null) {
            return;
        }
        if (!"active".equalsIgnoreCase(previousStatus) || !"suspended".equalsIgnoreCase(tenant.getStatus())) {
            return;
        }
        afterCommit(() -> cancelAddonOnlyStripeSubscriptions(tenant.getId()));
    }

    private void afterCommit(Runnable run) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    run.run();
                }
            });
        } else {
            run.run();
        }
    }

    private void rememberInvoicePayment(UUID tenantId, String invoiceId, String paymentIntentId) {
        if (tenantId == null || paymentIntentId == null || paymentIntentId.isBlank()) {
            return;
        }
        if (invoicePayments.existsByPaymentIntentId(paymentIntentId)) {
            return;
        }
        StripeInvoicePayment row = new StripeInvoicePayment();
        row.setPaymentIntentId(paymentIntentId);
        row.setInvoiceId(invoiceId == null ? "" : invoiceId);
        row.setTenantId(tenantId);
        invoicePayments.save(row);
    }

    private void syncUser(User user) {
        String tenantStatus = "";
        if (user.getTenantId() != null) {
            tenantStatus = tenants.findById(user.getTenantId()).map(Tenant::getStatus).orElse("");
        }
        channelEntitlements.syncAfterCommit(user.getTenantId(), user.getPlanId(), tenantStatus);
        planAccess.evict(user.getId());
    }

    private User resolveUser(JsonNode meta) {
        String uid = text(meta.path("user_id"));
        if (!uid.isBlank()) {
            try {
                return users.findById(UUID.fromString(uid)).orElse(null);
            } catch (IllegalArgumentException ignored) {
                /* fall through */
            }
        }
        String tid = text(meta.path("tenant_id"));
        if (!tid.isBlank()) {
            try {
                return users.findFirstByTenantIdAndTenantAdminTrue(UUID.fromString(tid)).orElse(null);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private User resolveUserFromSubscription(JsonNode invoice) {
        String subId = text(invoice.path("subscription"));
        if (subId.isBlank()) {
            subId = text(invoice.path("parent").path("subscription_details").path("subscription"));
        }
        if (subId.isBlank()) {
            return null;
        }
        StripeSubscription row = stripeSubscriptions.findById(subId).orElse(null);
        if (row == null || row.getTenantId() == null) {
            return null;
        }
        return users.findFirstByTenantIdAndTenantAdminTrue(row.getTenantId()).orElse(null);
    }

    private static JsonNode invoiceMetadata(JsonNode invoice) {
        JsonNode meta = invoice.path("metadata");
        if (meta.isMissingNode() || meta.isEmpty()) {
            meta = invoice.path("parent").path("subscription_details").path("metadata");
        }
        if (meta.isMissingNode() || meta.isEmpty()) {
            meta = invoice.path("subscription_details").path("metadata");
        }
        return meta;
    }

    private boolean isStaleLemon(User user, OffsetDateTime eventAt) {
        if (eventAt == null || user.getTenantId() == null) {
            return false;
        }
        Tenant tenant = tenants.findById(user.getTenantId()).orElse(null);
        if (tenant == null || tenant.getLemonSubscriptionUpdatedAt() == null) {
            return false;
        }
        return eventAt.isBefore(tenant.getLemonSubscriptionUpdatedAt());
    }

    private void touchLemon(Tenant tenant, OffsetDateTime eventAt, boolean subscriptionObject) {
        if (!subscriptionObject || eventAt == null) {
            return;
        }
        if (tenant.getLemonSubscriptionUpdatedAt() == null || !eventAt.isBefore(tenant.getLemonSubscriptionUpdatedAt())) {
            tenant.setLemonSubscriptionUpdatedAt(eventAt);
        }
    }

    private static boolean isActivate(String status, String eventName, Provider provider) {
        if (provider == Provider.LEMON) {
            return LEMON_ACTIVE.contains(status) || "subscription_created".equals(eventName);
        }
        return STRIPE_LIVE.contains(status) || "subscription_created".equals(eventName);
    }

    private static boolean isSuspend(String status, String eventName, Provider provider) {
        if (provider == Provider.LEMON) {
            return LEMON_SUSPENDED.contains(status) || eventName.contains("expired") || eventName.contains("paused");
        }
        return STRIPE_DEAD.contains(status);
    }

    private static Set<String> splitAddons(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String raw : csv.split(",")) {
            String code = raw.trim();
            if (!code.isEmpty()) {
                out.add(code);
            }
        }
        return out;
    }

    private static String joinAddons(Set<String> addons) {
        if (addons == null || addons.isEmpty()) {
            return "";
        }
        return String.join(",", addons);
    }

    private static String norm(String status) {
        return status == null ? "" : status.toLowerCase(Locale.ROOT);
    }

    private static OffsetDateTime unix(long seconds) {
        if (seconds <= 0) {
            return null;
        }
        return Instant.ofEpochSecond(seconds).atOffset(ZoneOffset.UTC);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return "";
        }
        return n.asText("");
    }
}
