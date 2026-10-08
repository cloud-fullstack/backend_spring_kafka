package com.omero.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.omero.auth.entity.BillingCommission;
import com.omero.auth.entity.SubscriptionPlan;
import com.omero.auth.entity.Tenant;
import com.omero.auth.entity.User;
import com.omero.auth.repository.BillingCommissionRepository;
import com.omero.auth.repository.LemonWebhookEventRepository;
import com.omero.auth.repository.SubscriptionPlanRepository;
import com.omero.auth.repository.TenantRepository;
import com.omero.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class LemonWebhookService {

    private static final Logger log = LoggerFactory.getLogger(LemonWebhookService.class);
    private static final Set<String> ACTIVE = Set.of("active", "on_trial");
    private static final Set<String> SUSPENDED = Set.of("expired", "unpaid", "paused");

    private final LemonWebhookEventRepository eventRepository;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final SubscriptionPlanRepository planRepository;
    private final BillingCommissionRepository commissionRepository;
    private final PlanAccessService planAccessService;

    public LemonWebhookService(
            LemonWebhookEventRepository eventRepository,
            UserRepository userRepository,
            TenantRepository tenantRepository,
            SubscriptionPlanRepository planRepository,
            BillingCommissionRepository commissionRepository,
            PlanAccessService planAccessService) {
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.planRepository = planRepository;
        this.commissionRepository = commissionRepository;
        this.planAccessService = planAccessService;
    }

    @Transactional
    public void handle(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return;
        }
        String eventName = text(root.path("meta").path("event_name"));
        String dataId = text(root.path("data").path("id"));
        String updated = text(root.path("data").path("attributes").path("updated_at"));
        String eventId = eventName + ":" + dataId + ":" + updated;
        if (!markNew(eventId, eventName)) {
            return;
        }

        JsonNode attrs = root.path("data").path("attributes");
        JsonNode custom = root.path("meta").path("custom_data");
        if ((custom.isMissingNode() || custom.isNull()) && attrs.has("custom_data")) {
            custom = attrs.path("custom_data");
        }

        Optional<User> user = resolveUser(custom, attrs);
        if (user.isEmpty()) {
            log.warn("Lemon webhook without matching user: {}", eventName);
            return;
        }
        User u = user.get();
        String status = text(attrs.path("status")).toLowerCase(Locale.ROOT);
        String planCode = resolvePlanCode(custom, attrs);
        OffsetDateTime eventUpdated = parseTime(updated);
        boolean isSubscriptionObject = "subscriptions".equals(text(root.path("data").path("type")));

        if (isSubscriptionObject || "order_created".equals(eventName)) {
            if (!isSubscriptionObject || !isStaleSubscriptionEvent(u, eventUpdated)) {
                applyPlanAndTenant(
                        u, planCode, status, eventName, eventUpdated, text(attrs.path("ends_at")), isSubscriptionObject);
            }
        }
        if ("subscription_payment_success".equals(eventName)) {
            String commissionPlan = (u.getPlanId() == null || "demo".equalsIgnoreCase(u.getPlanId()))
                    ? planCode
                    : u.getPlanId();
            accrueCommission(u, commissionPlan, attrs, dataId);
        }
        planAccessService.evict(u.getId());
    }

    private boolean markNew(String eventId, String eventName) {
        String name = eventName == null || eventName.isBlank() ? "unknown" : eventName;
        return eventRepository.insertIfAbsent(eventId, name) == 1;
    }

    private boolean isStaleSubscriptionEvent(User user, OffsetDateTime eventUpdated) {
        if (eventUpdated == null || user.getTenantId() == null) {
            return false;
        }
        Tenant tenant = tenantRepository.findById(user.getTenantId()).orElse(null);
        if (tenant == null || tenant.getLemonSubscriptionUpdatedAt() == null) {
            return false;
        }
        return eventUpdated.isBefore(tenant.getLemonSubscriptionUpdatedAt());
    }

    private Optional<User> resolveUser(JsonNode custom, JsonNode attrs) {
        String uid = text(custom.path("user_id"));
        if (!uid.isBlank()) {
            try {
                return userRepository.findById(UUID.fromString(uid));
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        String email = text(attrs.path("user_email"));
        if (email.isBlank()) {
            email = text(attrs.path("customer_email"));
        }
        if (!email.isBlank()) {
            return userRepository.findByEmailIgnoreCase(email.trim().toLowerCase(Locale.ROOT));
        }
        return Optional.empty();
    }

    private String resolvePlanCode(JsonNode custom, JsonNode attrs) {
        String variant = text(attrs.path("variant_id"));
        if (variant.isBlank() && attrs.path("first_order_item").has("variant_id")) {
            variant = text(attrs.path("first_order_item").path("variant_id"));
        }
        if (!variant.isBlank()) {
            Optional<String> fromVariant = planRepository.findByLemonVariantId(variant).map(SubscriptionPlan::getCode);
            if (fromVariant.isPresent()) {
                return fromVariant.get();
            }
        }
        String code = text(custom.path("plan_code"));
        if (!code.isBlank()) {
            return code.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private void applyPlanAndTenant(
            User user,
            String planCode,
            String lemonStatus,
            String eventName,
            OffsetDateTime eventUpdated,
            String endsAtRaw,
            boolean isSubscriptionObject) {
        if (planCode != null && !planCode.isBlank() && (ACTIVE.contains(lemonStatus) || "subscription_created".equals(eventName))) {
            user.setPlanId(planCode);
        }
        if (user.getTenantId() != null) {
            Tenant tenant = tenantRepository.findById(user.getTenantId()).orElse(null);
            if (tenant != null) {
                if (ACTIVE.contains(lemonStatus) || "subscription_created".equals(eventName) || "subscription_unpaused".equals(eventName)) {
                    tenant.setStatus("active");
                } else if (SUSPENDED.contains(lemonStatus) || eventName.contains("expired") || eventName.contains("paused")) {
                    tenant.setStatus("suspended");
                } else if ("cancelled".equals(lemonStatus)) {
                    OffsetDateTime endsAt = parseTime(endsAtRaw);
                    if (endsAt != null && !endsAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
                        tenant.setStatus("suspended");
                    }
                }
                if (isSubscriptionObject
                        && eventUpdated != null
                        && (tenant.getLemonSubscriptionUpdatedAt() == null
                        || !eventUpdated.isBefore(tenant.getLemonSubscriptionUpdatedAt()))) {
                    tenant.setLemonSubscriptionUpdatedAt(eventUpdated);
                }
                tenantRepository.save(tenant);
            }
        }
        userRepository.save(user);
    }

    private void accrueCommission(User user, String planCode, JsonNode attrs, String orderId) {
        if (user.getTenantId() == null) {
            return;
        }
        Tenant tenant = tenantRepository.findById(user.getTenantId()).orElse(null);
        if (tenant == null || tenant.getResellerId() == null) {
            return;
        }
        if (orderId != null && !orderId.isBlank() && commissionRepository.existsByLemonOrderId(orderId)) {
            return;
        }
        SubscriptionPlan plan = planCode == null ? null : planRepository.findByCodeIgnoreCase(planCode).orElse(null);
        BigDecimal percent = plan != null && plan.getResellerCommissionPercent() != null
                ? plan.getResellerCommissionPercent()
                : BigDecimal.ZERO;
        if (percent.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal net = cents(attrs.path("total")).subtract(cents(attrs.path("tax")));
        if (net.compareTo(BigDecimal.ZERO) <= 0) {
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
        row.setLemonOrderId(orderId);
        row.setStatus("pending");
        commissionRepository.save(row);
    }

    private static BigDecimal cents(JsonNode n) {
        if (n == null || !n.isNumber()) {
            return BigDecimal.ZERO;
        }
        return n.decimalValue().movePointLeft(2);
    }

    private static OffsetDateTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return "";
        }
        return n.asText("");
    }
}
