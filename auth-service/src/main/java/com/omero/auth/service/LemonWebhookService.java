package com.omero.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.omero.auth.entity.SubscriptionPlan;
import com.omero.auth.entity.User;
import com.omero.auth.repository.LemonWebhookEventRepository;
import com.omero.auth.repository.SubscriptionPlanRepository;
import com.omero.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class LemonWebhookService {

    private static final Logger log = LoggerFactory.getLogger(LemonWebhookService.class);

    private final LemonWebhookEventRepository eventRepository;
    private final UserRepository userRepository;
    private final SubscriptionPlanRepository planRepository;
    private final SubscriptionStateService subscriptionState;
    private final boolean testMode;

    public LemonWebhookService(
            LemonWebhookEventRepository eventRepository,
            UserRepository userRepository,
            SubscriptionPlanRepository planRepository,
            SubscriptionStateService subscriptionState,
            @Value("${lemonsqueezy.test-mode:true}") boolean testMode) {
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.planRepository = planRepository;
        this.subscriptionState = subscriptionState;
        this.testMode = testMode;
    }

    @Transactional
    public void handle(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return;
        }
        boolean payloadTest = root.path("meta").path("test_mode").asBoolean(false);
        if (payloadTest != testMode) {
            log.warn("Lemon webhook ignored: payload test_mode={} expected={}", payloadTest, testMode);
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
            subscriptionState.apply(
                    u,
                    planCode,
                    status,
                    eventName,
                    eventUpdated,
                    parseTime(text(attrs.path("ends_at"))),
                    SubscriptionStateService.Provider.LEMON,
                    isSubscriptionObject);
        }
        if ("subscription_payment_success".equals(eventName)) {
            String commissionPlan = (u.getPlanId() == null || "demo".equalsIgnoreCase(u.getPlanId()))
                    ? planCode
                    : u.getPlanId();
            BigDecimal net = cents(attrs.path("total")).subtract(cents(attrs.path("tax")));
            subscriptionState.accrue(u, commissionPlan, net, dataId, null);
        }
    }

    private boolean markNew(String eventId, String eventName) {
        String name = eventName == null || eventName.isBlank() ? "unknown" : eventName;
        return eventRepository.insertIfAbsent(eventId, name) == 1;
    }

    private Optional<User> resolveUser(JsonNode custom, JsonNode attrs) {
        String uid = text(custom.path("user_id"));
        if (uid.isBlank()) {
            uid = text(custom.path("userId"));
        }
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
        if (email.isBlank()) {
            email = text(custom.path("email"));
        }
        if (email.isBlank()) {
            email = text(attrs.path("customer").path("email"));
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
