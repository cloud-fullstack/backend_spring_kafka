package com.omero.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.omero.auth.entity.BillingAccount;
import com.omero.auth.entity.StripeSubscription;
import com.omero.auth.entity.SubscriptionPlan;
import com.omero.auth.entity.Tenant;
import com.omero.auth.entity.User;
import com.omero.auth.repository.SubscriptionPlanRepository;
import com.omero.auth.repository.TenantAddonRepository;
import com.omero.auth.repository.TenantRepository;
import com.omero.auth.web.PlanNotAvailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class StripeCheckoutService {

    private static final long MIN_CENTS = 500;
    private static final long MAX_CENTS = 5_000_000L;
    static final long ICAL_CENTS = 7_900L;
    static final long JOLLY_CENTS = 2_000L;
    static final long JOLLY_SETUP_CENTS = 4_900L;

    private final BillingAccountService accounts;
    private final TenantRepository tenants;
    private final TenantAddonRepository tenantAddons;
    private final SubscriptionPlanRepository plans;
    private final SubscriptionStateService subscriptions;
    private final LemonCheckoutService lemonCheckout;
    private final StripeApi stripeApi;
    private final CancellationNoticeService cancellationNotices;
    private final String taxCode;
    private final String frontendBase;

    public StripeCheckoutService(
            BillingAccountService accounts,
            TenantRepository tenants,
            TenantAddonRepository tenantAddons,
            SubscriptionPlanRepository plans,
            SubscriptionStateService subscriptions,
            LemonCheckoutService lemonCheckout,
            StripeApi stripeApi,
            CancellationNoticeService cancellationNotices,
            @Value("${stripe.credits-tax-code:txcd_10105002}") String taxCode,
            @Value("${app.frontend-base-url}") String frontendBase) {
        this.accounts = accounts;
        this.tenants = tenants;
        this.tenantAddons = tenantAddons;
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.lemonCheckout = lemonCheckout;
        this.stripeApi = stripeApi;
        this.cancellationNotices = cancellationNotices;
        this.taxCode = taxCode;
        this.frontendBase = frontendBase;
    }

    public String subscriptionCheckoutUrl(UUID userId, String planCode, List<String> extraAddons) {
        User user = accounts.requireUser(userId);
        SubscriptionPlan plan = plans.findByCodeIgnoreCase(planCode == null ? "" : planCode.trim())
                .orElseThrow(() -> new IllegalArgumentException("Invalid request"));
        if ("jolly".equalsIgnoreCase(plan.getCode())) {
            return jollyCheckoutUrl(userId, null);
        }
        if (!plan.catalogAvailable()) {
            throw new PlanNotAvailableException();
        }
        if (plan.isOneOff()) {
            return oneOffCheckoutUrl(userId, plan.getCode());
        }
        if (plan.getStripePriceId() == null || plan.getStripePriceId().isBlank()) {
            throw new IllegalArgumentException("Invalid request");
        }
        List<SubscriptionPlan> addonPlans = resolveAddons(plan, extraAddons);
        if (plan.isAddon() && !hasPaidSubscription(user)) {
            throw new PlanNotAvailableException();
        }
        assertRequirements(user, plan, addonPlans);
        if (plan.isAddon()) {
            Optional<StripeSubscription> live = subscriptions.livePlanSubscription(user.getTenantId());
            if (live.isPresent()) {
                long stamp = live.get().getEventAt() == null ? 0L : live.get().getEventAt().toEpochSecond();
                addSubscriptionItem(live.get().getSubscriptionId(), plan.getStripePriceId(), plan.getCode(), stamp);
                return frontendBase.replaceAll("/$", "")
                        + "/billing/success?kind=addon&plan=" + plan.getCode();
            }
        }
        if (!plan.isAddon() && addonPlans.isEmpty() && hasPaidSubscription(user)) {
            return billingPortalUrl(user);
        }
        BillingAccount account = accounts.ensure(user.getTenantId());
        String base = frontendBase.replaceAll("/$", "");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("mode", "subscription");
        form.add("success_url", base + "/billing/success?session_id={CHECKOUT_SESSION_ID}");
        form.add("cancel_url", base + "/dashboard?tab=plans");
        form.add("client_reference_id", user.getId().toString());
        attachCustomer(form, account, user);
        String kind = plan.isAddon() ? "addon" : "plan";
        putMeta(form, "metadata", user, plan.getCode(), kind, addonPlans);
        putMeta(form, "subscription_data[metadata]", user, plan.getCode(), kind, addonPlans);
        form.add("line_items[0][quantity]", "1");
        form.add("line_items[0][price]", plan.getStripePriceId());
        int i = 1;
        for (SubscriptionPlan addon : addonPlans) {
            form.add("line_items[" + i + "][quantity]", "1");
            form.add("line_items[" + i + "][price]", addon.getStripePriceId());
            i++;
        }
        form.add("managed_payments[enabled]", "true");
        return postCheckout(form);
    }

    public String billingPortalUrl(UUID userId) {
        return billingPortalUrl(accounts.requireUser(userId));
    }

    public String billingPortalUrl(User user) {
        BillingAccount account = accounts.ensure(user.getTenantId());
        if (account.getStripeCustomerId() == null || account.getStripeCustomerId().isBlank()) {
            return frontendBase.replaceAll("/$", "") + "/dashboard?tab=plans";
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("customer", account.getStripeCustomerId());
        form.add("return_url", frontendBase.replaceAll("/$", "") + "/dashboard?tab=billing");
        JsonNode url = stripeApi.post("/v1/billing_portal/sessions", form, null).path("url");
        if (url.isMissingNode() || url.asText().isBlank()) {
            throw new IllegalStateException("Stripe is not configured");
        }
        return url.asText();
    }

    public Map<String, Object> paymentMethodInfo(UUID userId) {
        User user = accounts.requireUser(userId);
        BillingAccount account = accounts.ensure(user.getTenantId());
        Map<String, Object> pm = new LinkedHashMap<>();
        if (account.getCardLast4() != null && !account.getCardLast4().isBlank()) {
            pm.put("type", "card");
            pm.put("last4", account.getCardLast4());
        }
        String cid = account.getStripeCustomerId();
        if (cid == null || cid.isBlank()) {
            return Map.of("paymentMethod", pm, "hasCustomer", false);
        }
        try {
            JsonNode list = stripeApi.get("/v1/customers/" + cid + "/payment_methods?limit=5");
            if (list != null) {
                for (JsonNode node : list.path("data")) {
                    Map<String, Object> parsed = parsePaymentMethod(node);
                    if (!parsed.isEmpty()) {
                        pm = parsed;
                        break;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // last4 da billing_accounts resta visibile
        }
        return Map.of("paymentMethod", pm, "hasCustomer", true);
    }

    private static Map<String, Object> parsePaymentMethod(JsonNode pm) {
        Map<String, Object> out = new LinkedHashMap<>();
        String type = StripeApi.text(pm.path("type"));
        if (type.isBlank()) {
            return out;
        }
        out.put("type", type);
        JsonNode card = pm.path("card");
        if (card.isObject() && card.size() > 0) {
            out.put("brand", StripeApi.text(card.path("brand")));
            out.put("last4", StripeApi.text(card.path("last4")));
            if (card.path("exp_month").canConvertToInt()) {
                out.put("expMonth", card.path("exp_month").asInt());
            }
            if (card.path("exp_year").canConvertToInt()) {
                out.put("expYear", card.path("exp_year").asInt());
            }
        }
        if ("paypal".equalsIgnoreCase(type)) {
            out.put("email", StripeApi.text(pm.path("paypal").path("payer_email")));
        }
        return out;
    }

    public Map<String, Object> scheduleCancel(UUID userId, String reason) {
        User user = accounts.requireUser(userId);
        Optional<StripeSubscription> live = subscriptions.livePlanSubscription(user.getTenantId());
        if (live.isEmpty()) {
            Tenant tenant = tenants.findById(user.getTenantId()).orElse(null);
            if (tenant != null && tenant.getLemonSubscriptionUpdatedAt() != null) {
                return Map.of("url", lemonCheckout.customerPortalUrl());
            }
            throw new IllegalArgumentException("Invalid request");
        }
        StripeSubscription sub = live.get();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("cancel_at_period_end", "true");
        form.add("cancellation_details[comment]", reason == null ? "" : reason.strip());
        JsonNode updated = stripeApi.post("/v1/subscriptions/" + sub.getSubscriptionId(), form, null);
        OffsetDateTime endsAt = SubscriptionStateService.periodEnd(updated);
        if (endsAt == null) {
            endsAt = sub.getPeriodEnd();
        } else {
            sub.setPeriodEnd(endsAt);
        }
        cancellationNotices.confirmScheduled(user, endsAt);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cancelAtPeriodEnd", true);
        out.put("endsAt", endsAt == null ? null : endsAt.toString());
        return out;
    }

    public Map<String, Object> resume(UUID userId) {
        User user = accounts.requireUser(userId);
        StripeSubscription sub = subscriptions.livePlanSubscription(user.getTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Invalid request"));
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("cancel_at_period_end", "false");
        stripeApi.post("/v1/subscriptions/" + sub.getSubscriptionId(), form, null);
        return Map.of("cancelAtPeriodEnd", false);
    }

    public Map<String, Object> checkoutSessionSummary(UUID userId, String sessionId) {
        String sid = sessionId == null ? "" : sessionId.trim();
        if (!validCheckoutSessionId(sid)) {
            throw new IllegalArgumentException("Invalid request");
        }
        JsonNode session = stripeApi.get("/v1/checkout/sessions/" + sid + "?expand[]=subscription");
        if (session == null) {
            throw new IllegalArgumentException("Invalid request");
        }
        String owner = StripeApi.text(session.path("metadata").path("user_id"));
        if (owner.isBlank()) {
            owner = StripeApi.text(session.path("client_reference_id"));
        }
        if (!userId.toString().equalsIgnoreCase(owner)) {
            throw new IllegalArgumentException("Invalid request");
        }
        String kind = StripeApi.text(session.path("metadata").path("kind"));
        if (kind.isBlank()) {
            kind = "subscription".equalsIgnoreCase(StripeApi.text(session.path("mode"))) ? "plan" : "credits";
        }
        String planCode = StripeApi.text(session.path("metadata").path("plan_code"));
        String planName = planCode;
        if (!planCode.isBlank()) {
            Optional<SubscriptionPlan> plan = plans.findByCodeIgnoreCase(planCode);
            if (plan.isPresent() && plan.get().getName() != null && !plan.get().getName().isBlank()) {
                planName = plan.get().getName();
            }
        }
        String paymentStatus = StripeApi.text(session.path("payment_status"));
        String status = StripeApi.text(session.path("status"));
        boolean paid = "paid".equalsIgnoreCase(paymentStatus)
                || "no_payment_required".equalsIgnoreCase(paymentStatus)
                || "complete".equalsIgnoreCase(status);
        OffsetDateTime renews = subscriptionRenewal(session.path("subscription"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paid", paid);
        out.put("kind", kind);
        out.put("planCode", planCode);
        out.put("planName", planName);
        out.put("amountCents", session.path("amount_total").asLong(0));
        out.put("currency", StripeApi.text(session.path("currency")));
        out.put("renewsAt", renews == null ? null : renews.toString());
        out.put("status", status);
        return out;
    }

    public record CreatedCheckout(String url, String sessionId) {}

    /**
     * Jolly 20 €/mese: Price ID reale se c'è, altrimenti price_data (niente ID inventato).
     * Con numberRequestId: setup 49 € come add_invoice_item sulla prima fattura.
     */
    public String jollyCheckoutUrl(UUID userId, UUID numberRequestId) {
        return createJollyCheckout(userId, numberRequestId).url();
    }

    public CreatedCheckout createJollyCheckout(UUID userId, UUID numberRequestId) {
        User user = accounts.requireUser(userId);
        if (user.getTenantId() == null) {
            throw new PlanNotAvailableException();
        }
        if (tenantAddons.existsByTenantIdAndAddonCodeAndStatusIgnoreCase(
                user.getTenantId(), "jolly", "active")) {
            throw new PlanNotAvailableException();
        }
        BillingAccount account = accounts.ensure(user.getTenantId());
        String base = frontendBase.replaceAll("/$", "");
        String kind = numberRequestId != null ? "jolly_with_setup" : "jolly";
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("mode", "subscription");
        form.add("success_url", base + "/billing/success?session_id={CHECKOUT_SESSION_ID}");
        form.add("cancel_url", base + "/dashboard?tab=plans");
        form.add("client_reference_id", user.getId().toString());
        attachCustomer(form, account, user);
        form.add("metadata[user_id]", user.getId().toString());
        form.add("metadata[tenant_id]", user.getTenantId().toString());
        form.add("metadata[kind]", kind);
        form.add("metadata[plan_code]", "jolly");
        form.add("subscription_data[metadata][user_id]", user.getId().toString());
        form.add("subscription_data[metadata][tenant_id]", user.getTenantId().toString());
        form.add("subscription_data[metadata][kind]", kind);
        form.add("subscription_data[metadata][plan_code]", "jolly");
        if (numberRequestId != null) {
            form.add("metadata[number_request_id]", numberRequestId.toString());
            form.add("subscription_data[metadata][number_request_id]", numberRequestId.toString());
        }
        long jollyCents = planCents("jolly", JOLLY_CENTS);
        form.add("line_items[0][quantity]", "1");
        if (hasStripePrice("jolly")) {
            form.add("line_items[0][price]", plans.findByCodeIgnoreCase("jolly").orElseThrow().getStripePriceId());
        } else {
            form.add("line_items[0][price_data][currency]", "eur");
            form.add("line_items[0][price_data][unit_amount]", Long.toString(jollyCents));
            form.add("line_items[0][price_data][recurring][interval]", "month");
            form.add("line_items[0][price_data][tax_behavior]", "exclusive");
            form.add("line_items[0][price_data][product_data][name]", "Telemaco Jolly");
            form.add("line_items[0][price_data][product_data][tax_code]", taxCode);
        }
        if (numberRequestId != null) {
            long setupCents = planCents("whatsapp_number_setup", JOLLY_SETUP_CENTS);
            form.add("subscription_data[add_invoice_items][0][quantity]", "1");
            if (hasStripePrice("whatsapp_number_setup")) {
                form.add("subscription_data[add_invoice_items][0][price]",
                        plans.findByCodeIgnoreCase("whatsapp_number_setup").orElseThrow().getStripePriceId());
            } else {
                form.add("subscription_data[add_invoice_items][0][price_data][currency]", "eur");
                form.add("subscription_data[add_invoice_items][0][price_data][unit_amount]", Long.toString(setupCents));
                form.add("subscription_data[add_invoice_items][0][price_data][product_data][name]",
                        "Setup Numero WhatsApp Business");
            }
        }
        form.add("managed_payments[enabled]", "true");
        JsonNode session = stripeApi.post("/v1/checkout/sessions", form, null);
        String url = session == null ? "" : session.path("url").asText("");
        if (url.isBlank()) {
            throw new IllegalStateException("Stripe is not configured");
        }
        return new CreatedCheckout(url, session.path("id").asText(""));
    }

    private long planCents(String code, long fallback) {
        return plans.findByCodeIgnoreCase(code)
                .map(p -> p.getPrice() == null ? fallback : p.getPrice().movePointRight(2).longValue())
                .orElse(fallback);
    }

    private boolean hasStripePrice(String code) {
        return plans.findByCodeIgnoreCase(code)
                .map(p -> p.getStripePriceId() != null && !p.getStripePriceId().isBlank())
                .orElse(false);
    }

    /** One-off catalogato (es. vehicle_api €99). iCal resta icalCheckoutUrl (€79). */
    public String oneOffCheckoutUrl(UUID userId, String planCode) {
        String code = planCode == null ? "" : planCode.trim().toLowerCase();
        if ("ical".equals(code) || "ical_integration".equals(code)) {
            return icalCheckoutUrl(userId);
        }
        SubscriptionPlan plan = plans.findByCodeIgnoreCase(code)
                .orElseThrow(() -> new IllegalArgumentException("Invalid request"));
        if (!plan.isAddon() || !plan.isOneOff() || !plan.catalogAvailable()) {
            throw new PlanNotAvailableException();
        }
        if (plan.getPrice() == null) {
            throw new IllegalArgumentException("Invalid request");
        }
        User user = accounts.requireUser(userId);
        if (user.getTenantId() != null
                && tenantAddons.existsByTenantIdAndAddonCodeAndStatusIgnoreCase(
                        user.getTenantId(), plan.getCode(), "active")) {
            throw new PlanNotAvailableException();
        }
        BillingAccount account = accounts.ensure(user.getTenantId());
        String base = frontendBase.replaceAll("/$", "");
        long cents = plan.getPrice().movePointRight(2).longValueExact();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("mode", "payment");
        form.add("success_url", base + "/billing/success?session_id={CHECKOUT_SESSION_ID}");
        form.add("cancel_url", base + "/dashboard?tab=plans");
        form.add("client_reference_id", user.getId().toString());
        attachCustomer(form, account, user);
        form.add("metadata[user_id]", user.getId().toString());
        form.add("metadata[tenant_id]", user.getTenantId().toString());
        form.add("metadata[kind]", plan.getCode());
        form.add("metadata[plan_code]", plan.getCode());
        form.add("line_items[0][quantity]", "1");
        form.add("line_items[0][price_data][currency]", "eur");
        form.add("line_items[0][price_data][unit_amount]", Long.toString(cents));
        form.add("line_items[0][price_data][tax_behavior]", "exclusive");
        form.add("line_items[0][price_data][product_data][name]", "Telemaco " + plan.getName());
        form.add("line_items[0][price_data][product_data][tax_code]", taxCode);
        form.add("managed_payments[enabled]", "true");
        return postCheckout(form);
    }

    /**
     * Solo la sessione Checkout €79. Dopo il pagamento:
     * StripeWebhookService.activateOneOff
     *   → ChannelEntitlementClient.activateAddon  @Transactional
     *       ├── addons.save(row)                  INSERT tenant_addons
     *       └── outbox.enqueueFeature()           INSERT outbox_events  ← stessa TX
     */
    public String icalCheckoutUrl(UUID userId) {
        User user = accounts.requireUser(userId);
        if (user.getTenantId() != null
                && tenantAddons.existsByTenantIdAndAddonCodeAndStatusIgnoreCase(
                        user.getTenantId(), "ical_integration", "active")) {
            throw new PlanNotAvailableException();
        }
        BillingAccount account = accounts.ensure(user.getTenantId());
        String base = frontendBase.replaceAll("/$", "");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("mode", "payment");
        form.add("success_url", base + "/billing/success?session_id={CHECKOUT_SESSION_ID}");
        form.add("cancel_url", base + "/dashboard?tab=chatbot");
        form.add("client_reference_id", user.getId().toString());
        attachCustomer(form, account, user);
        form.add("metadata[user_id]", user.getId().toString());
        form.add("metadata[tenant_id]", user.getTenantId().toString());
        form.add("metadata[kind]", "ical");
        form.add("metadata[plan_code]", "ical_integration");
        form.add("line_items[0][quantity]", "1");
        form.add("line_items[0][price_data][currency]", "eur");
        form.add("line_items[0][price_data][unit_amount]", Long.toString(ICAL_CENTS));
        form.add("line_items[0][price_data][tax_behavior]", "exclusive");
        form.add("line_items[0][price_data][product_data][name]", "Telemaco iCal sync");
        form.add("line_items[0][price_data][product_data][tax_code]", taxCode);
        form.add("managed_payments[enabled]", "true");
        return postCheckout(form);
    }

    public String creditsCheckoutUrl(UUID userId, long amountCents) {
        if (amountCents < MIN_CENTS || amountCents > MAX_CENTS) {
            throw new IllegalArgumentException("Invalid amount");
        }
        User user = accounts.requireUser(userId);
        BillingAccount account = accounts.ensure(user.getTenantId());
        String base = frontendBase.replaceAll("/$", "");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("mode", "payment");
        form.add("success_url", base + "/billing/success?session_id={CHECKOUT_SESSION_ID}");
        form.add("cancel_url", base + "/dashboard?tab=billing");
        form.add("client_reference_id", user.getId().toString());
        attachCustomer(form, account, user);
        form.add("metadata[user_id]", user.getId().toString());
        form.add("metadata[tenant_id]", user.getTenantId().toString());
        form.add("metadata[kind]", "credits");
        form.add("metadata[credit_cents]", Long.toString(amountCents));
        form.add("line_items[0][quantity]", "1");
        form.add("line_items[0][price_data][currency]", "eur");
        form.add("line_items[0][price_data][unit_amount]", Long.toString(amountCents));
        form.add("line_items[0][price_data][tax_behavior]", "exclusive");
        form.add("line_items[0][price_data][product_data][name]", "Telemaco credits");
        form.add("line_items[0][price_data][product_data][tax_code]", taxCode);
        form.add("managed_payments[enabled]", "true");
        return postCheckout(form);
    }

    private String postCheckout(MultiValueMap<String, String> form) {
        JsonNode url = stripeApi.post("/v1/checkout/sessions", form, null).path("url");
        if (url.isMissingNode() || url.asText().isBlank()) {
            throw new IllegalStateException("Stripe is not configured");
        }
        return url.asText();
    }

    private void addSubscriptionItem(String subscriptionId, String priceId, String code, long eventAtEpoch) {
        try {
            JsonNode sub = stripeApi.get("/v1/subscriptions/" + subscriptionId);
            if (sub != null) {
                for (JsonNode it : sub.path("items").path("data")) {
                    String existing = StripeApi.text(it.path("price").path("id"));
                    if (existing.isBlank()) {
                        existing = StripeApi.text(it.path("price"));
                    }
                    if (priceId.equals(existing)) {
                        return;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // idempotency key still prevents a double item
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("subscription", subscriptionId);
        form.add("price", priceId);
        form.add("proration_behavior", "create_prorations");
        String key = "addon:" + subscriptionId + ":" + (code == null ? "" : code.toLowerCase())
                + ":" + eventAtEpoch;
        stripeApi.post("/v1/subscription_items", form, key);
    }

    private void attachCustomer(MultiValueMap<String, String> form, BillingAccount account, User user) {
        if (account.getStripeCustomerId() != null && !account.getStripeCustomerId().isBlank()) {
            form.add("customer", account.getStripeCustomerId());
        } else {
            form.add("customer_email", user.getEmail());
            form.add("customer_creation", "always");
        }
    }

    private void putMeta(
            MultiValueMap<String, String> form,
            String prefix,
            User user,
            String planCode,
            String kind,
            List<SubscriptionPlan> addonPlans) {
        form.add(prefix + "[user_id]", user.getId().toString());
        form.add(prefix + "[tenant_id]", user.getTenantId().toString());
        form.add(prefix + "[plan_code]", planCode);
        form.add(prefix + "[kind]", kind);
        if (!addonPlans.isEmpty()) {
            StringBuilder codes = new StringBuilder();
            for (SubscriptionPlan a : addonPlans) {
                if (!codes.isEmpty()) {
                    codes.append(',');
                }
                codes.append(a.getCode());
            }
            form.add(prefix + "[addon_codes]", codes.toString());
        }
    }

    private List<SubscriptionPlan> resolveAddons(SubscriptionPlan primary, List<String> extraAddons) {
        if (primary.isAddon() || extraAddons == null || extraAddons.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<SubscriptionPlan> out = new ArrayList<>();
        for (String raw : extraAddons) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String code = raw.trim().toLowerCase();
            if (!seen.add(code) || code.equalsIgnoreCase(primary.getCode())) {
                continue;
            }
            SubscriptionPlan addon = plans.findByCodeIgnoreCase(code)
                    .orElseThrow(() -> new IllegalArgumentException("Invalid request"));
            if (!addon.isAddon() || !addon.catalogAvailable() || addon.isOneOff()) {
                throw new PlanNotAvailableException();
            }
            if (addon.getStripePriceId() == null || addon.getStripePriceId().isBlank()) {
                throw new IllegalArgumentException("Invalid request");
            }
            out.add(addon);
        }
        return out;
    }

    private static boolean validCheckoutSessionId(String sid) {
        if (sid.length() < 8 || sid.length() > 255 || !sid.startsWith("cs_")) {
            return false;
        }
        for (int i = 0; i < sid.length(); i++) {
            char c = sid.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                continue;
            }
            return false;
        }
        return true;
    }

    private OffsetDateTime subscriptionRenewal(JsonNode subscription) {
        if (subscription == null || subscription.isMissingNode() || subscription.isNull()) {
            return null;
        }
        if (subscription.isObject() && subscription.size() > 0) {
            return SubscriptionStateService.periodEnd(subscription);
        }
        String subId = StripeApi.text(subscription);
        if (subId.isBlank() || !subId.startsWith("sub_")) {
            return null;
        }
        JsonNode fetched = stripeApi.get("/v1/subscriptions/" + subId);
        return fetched == null ? null : SubscriptionStateService.periodEnd(fetched);
    }

    boolean hasPaidSubscription(User user) {
        if (user.getTenantId() == null) {
            return false;
        }
        Tenant tenant = tenants.findById(user.getTenantId()).orElse(null);
        return tenant != null && "active".equalsIgnoreCase(tenant.getStatus());
    }

    private void assertRequirements(User user, SubscriptionPlan primary, List<SubscriptionPlan> extraAddons) {
        Set<String> have = new LinkedHashSet<>();
        if (user.getPlanId() != null && !user.getPlanId().isBlank()) {
            have.add(user.getPlanId().toLowerCase());
        }
        if (user.getTenantId() != null) {
            tenantAddons.findByTenantId(user.getTenantId()).forEach(row -> {
                if (row.getAddonCode() != null && "active".equalsIgnoreCase(row.getStatus())) {
                    have.add(row.getAddonCode().toLowerCase());
                }
            });
        }
        if (primary.isAddon() && have.contains(primary.getCode().toLowerCase())) {
            throw new PlanNotAvailableException();
        }
        if (!primary.isAddon()) {
            have.add(primary.getCode().toLowerCase());
        }
        for (SubscriptionPlan extra : extraAddons) {
            if (have.contains(extra.getCode().toLowerCase())) {
                throw new PlanNotAvailableException();
            }
            have.add(extra.getCode().toLowerCase());
        }
        List<SubscriptionPlan> check = new ArrayList<>();
        if (primary.isAddon()) {
            check.add(primary);
        }
        check.addAll(extraAddons);
        for (SubscriptionPlan item : check) {
            List<String> needs = item.requirementCodes();
            if (needs.isEmpty()) {
                continue;
            }
            boolean ok = false;
            for (String req : needs) {
                if (have.contains(req)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                throw new PlanNotAvailableException();
            }
        }
    }
}
