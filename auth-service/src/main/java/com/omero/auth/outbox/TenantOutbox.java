package com.omero.auth.outbox;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * INSERT outbox_events nella TX corrente. Non parla con Redis.
 *
 * StripeWebhookService.activateIcal
 *   → ChannelEntitlementClient.activateAddon  @Transactional
 *       ├── addons.save(row)                  INSERT tenant_addons
 *       └── outbox.enqueueFeature()           INSERT outbox_events  ← stessa TX
 *   COMMIT ─────────────────────────────────────────────────────────────
 *
 * OutboxRelay.relay()  ogni 2 s  @Scheduled  @Transactional
 *   ├── outbox.lockNextBatch()               SELECT FOR UPDATE SKIP LOCKED
 *   ├── publisher.publish(event)             XADD telemacos:tenant:feature
 *   └── event.markPublished()                UPDATE outbox_events
 */
@Service
public class TenantOutbox {

    public static final String PAID = "tenant.paid";
    public static final String TRIAL_WEEK = "tenant.trial.week";
    public static final String FEATURE = "tenant.feature";
    public static final String DEACTIVATED = "tenant.deactivated";

    private final OutboxRepository outbox;

    public TenantOutbox(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    public void enqueuePaid(UUID tenantId, String sector, String plan,
                            List<String> featuresUnlocked, String paymentRef) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", PAID);
        payload.put("tenant_id", tenantId.toString());
        payload.put("sector", sector == null ? "" : sector);
        payload.put("plan", plan == null ? "" : plan);
        payload.put("features_unlocked", featuresUnlocked == null ? List.of() : featuresUnlocked);
        payload.put("payment_reference", paymentRef == null ? "" : paymentRef);
        payload.put("timestamp", Instant.now().toString());
        save(tenantId, PAID, payload);
    }

    public void enqueueTrialWeek(UUID tenantId, String sector, String tenantName, Instant trialStartedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", TRIAL_WEEK);
        payload.put("tenant_id", tenantId.toString());
        payload.put("sector", sector == null ? "" : sector);
        payload.put("tenant_name", tenantName == null ? "" : tenantName);
        payload.put("trial_started_at", trialStartedAt == null ? "" : trialStartedAt.toString());
        payload.put("timestamp", Instant.now().toString());
        save(tenantId, TRIAL_WEEK, payload);
    }

    public void enqueueFeature(UUID tenantId, String feature, boolean enable) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", FEATURE);
        payload.put("tenant_id", tenantId.toString());
        payload.put("feature", feature == null ? "" : feature);
        payload.put("action", enable ? "enable" : "disable");
        payload.put("timestamp", Instant.now().toString());
        save(tenantId, FEATURE, payload);
    }

    public void enqueueDeactivated(UUID tenantId, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", DEACTIVATED);
        payload.put("tenant_id", tenantId.toString());
        payload.put("reason", reason == null ? "" : reason);
        payload.put("timestamp", Instant.now().toString());
        save(tenantId, DEACTIVATED, payload);
    }

    private void save(UUID tenantId, String type, Map<String, Object> payload) {
        if (tenantId == null) {
            return;
        }
        outbox.save(new OutboxEvent(tenantId.toString(), type, payload));
    }
}
