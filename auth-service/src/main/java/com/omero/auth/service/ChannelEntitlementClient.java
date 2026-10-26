package com.omero.auth.service;

import com.omero.auth.entity.SubscriptionPlan;
import com.omero.auth.entity.Tenant;
import com.omero.auth.entity.TenantAddon;
import com.omero.auth.entity.User;
import com.omero.auth.repository.SubscriptionPlanRepository;
import com.omero.auth.repository.TenantAddonRepository;
import com.omero.auth.repository.TenantRepository;
import com.omero.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class ChannelEntitlementClient {

    private static final Logger log = LoggerFactory.getLogger(ChannelEntitlementClient.class);
    private static final Set<String> WA_PLANS = Set.of("gold", "platinum");

    private final RestTemplate http;
    private final SubscriptionPlanRepository plans;
    private final TenantAddonRepository addons;
    private final UserRepository users;
    private final TenantRepository tenants;
    private final String url;
    private final String token;
    private final String llmUrl;
    private final String billingEnv;
    private final String llmToken;

    public ChannelEntitlementClient(
            RestTemplate http,
            SubscriptionPlanRepository plans,
            TenantAddonRepository addons,
            UserRepository users,
            TenantRepository tenants,
            @Value("${channels.internal-url:}") String url,
            @Value("${channels.internal-token:}") String token,
            @Value("${llm.entitlements-url:}") String llmUrl,
            @Value("${llm.entitlements-env:}") String billingEnv,
            @Value("${llm.internal-token:}") String llmToken) {
        this.http = http;
        this.plans = plans;
        this.addons = addons;
        this.users = users;
        this.tenants = tenants;
        this.url = url == null ? "" : url.trim();
        this.token = token == null ? "" : token;
        this.llmUrl = llmUrl == null ? "" : llmUrl.trim();
        this.billingEnv = billingEnv == null ? "" : billingEnv.trim().toLowerCase(Locale.ROOT);
        this.llmToken = llmToken == null ? "" : llmToken;
    }

    @jakarta.annotation.PostConstruct
    void checkEnv() {
        if (!llmUrl.isBlank() && !Set.of("dev", "prod").contains(billingEnv)) {
            throw new IllegalStateException("llm.entitlements-env deve essere dev o prod");
        }
    }

    public void syncAfterCommit(UUID tenantId, String planCode, String tenantStatus) {
        if (tenantId == null) {
            return;
        }
        Runnable run = () -> syncPlan(tenantId, planCode, tenantStatus);
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

    public void syncPlan(UUID tenantId, String planCode, String tenantStatus) {
        if (tenantId == null) {
            return;
        }
        if (!url.isBlank()) {
            boolean included = whatsappIncluded(planCode)
                    || addons.existsByTenantIdAndAddonCodeAndStatusIgnoreCase(tenantId, "whatsapp", "active");
            boolean wa = included && "active".equalsIgnoreCase(tenantStatus);
            try {
                HttpHeaders h = new HttpHeaders();
                h.setContentType(MediaType.APPLICATION_JSON);
                h.set("X-Internal-Token", token);
                http.postForEntity(
                        url.replaceAll("/$", "") + "/internal/whatsapp/entitlements",
                        new HttpEntity<>(Map.of("tenantId", tenantId.toString(), "whatsapp", wa), h),
                        Void.class);
            } catch (Exception e) {
                log.warn("channels entitlement sync failed tenant={}", tenantId);
            }
        }
        syncWidget(tenantId, planCode, tenantStatus);
    }

    public void activateAddon(UUID tenantId, String code) {
        activateAddon(tenantId, code, "stripe");
    }

    public void activateAddon(UUID tenantId, String code, String source) {
        if (tenantId == null || code == null || code.isBlank()) {
            return;
        }
        TenantAddon.Key key = new TenantAddon.Key();
        key.setTenantId(tenantId);
        key.setAddonCode(code.toLowerCase(Locale.ROOT));
        TenantAddon existing = addons.findById(key).orElse(null);
        String origin = source == null ? "manual" : source;
        if (existing != null) {
            String rowSource = existing.getSource() == null || existing.getSource().isBlank()
                    ? "manual" : existing.getSource();
            boolean otherActive = !origin.equalsIgnoreCase(rowSource)
                    && "active".equalsIgnoreCase(existing.getStatus());
            if (otherActive) {
                return;
            }
            existing.setSource(origin);
            existing.setStatus("active");
            addons.save(existing);
            return;
        }
        TenantAddon row = new TenantAddon();
        row.setTenantId(tenantId);
        row.setAddonCode(code.toLowerCase(Locale.ROOT));
        row.setStatus("active");
        row.setSource(origin);
        addons.save(row);
    }

    public void deactivateAddon(UUID tenantId, String code) {
        deactivateAddon(tenantId, code, null);
    }

    public void deactivateAddon(UUID tenantId, String code, String source) {
        if (tenantId == null || code == null) {
            return;
        }
        TenantAddon.Key key = new TenantAddon.Key();
        key.setTenantId(tenantId);
        key.setAddonCode(code.toLowerCase(Locale.ROOT));
        addons.findById(key).ifPresent(row -> {
            if (source != null && row.getSource() != null && !source.equalsIgnoreCase(row.getSource())) {
                return;
            }
            row.setStatus("inactive");
            addons.save(row);
        });
    }

    public void replaceAddons(UUID tenantId, Set<String> activeCodes) {
        replaceAddons(tenantId, activeCodes, "stripe");
    }

    public void replaceAddons(UUID tenantId, Set<String> activeCodes, String source) {
        if (tenantId == null) {
            return;
        }
        String origin = source == null ? "stripe" : source.toLowerCase(Locale.ROOT);
        Set<String> want = new HashSet<>();
        if (activeCodes != null) {
            for (String code : activeCodes) {
                if (code != null && !code.isBlank()) {
                    want.add(code.toLowerCase(Locale.ROOT));
                }
            }
        }
        Set<String> seen = new HashSet<>();
        for (TenantAddon row : addons.findByTenantId(tenantId)) {
            String rowSource = row.getSource() == null || row.getSource().isBlank() ? "manual" : row.getSource();
            if (!origin.equalsIgnoreCase(rowSource)) {
                continue;
            }
            String code = row.getAddonCode() == null ? "" : row.getAddonCode().toLowerCase(Locale.ROOT);
            seen.add(code);
            if (want.contains(code)) {
                if (!"active".equalsIgnoreCase(row.getStatus())) {
                    row.setStatus("active");
                    addons.save(row);
                }
            } else if ("active".equalsIgnoreCase(row.getStatus())) {
                row.setStatus("inactive");
                addons.save(row);
            }
        }
        for (String code : want) {
            if (!seen.contains(code)) {
                activateAddon(tenantId, code, origin);
            }
        }
    }

    // Con replicaCount > 1 serve ShedLock, come per commissioni/payout.
    @Scheduled(cron = "0 15 * * * *")
    public void reconcileHourly() {
        if (url.isBlank() && llmUrl.isBlank()) {
            return;
        }
        for (Tenant t : tenants.findAll()) {
            users.findFirstByTenantIdAndTenantAdminTrue(t.getId()).ifPresent(admin ->
                    syncPlan(t.getId(), admin.getPlanId(), t.getStatus() == null ? "" : t.getStatus()));
        }
    }

    private void syncWidget(UUID tenantId, String planCode, String tenantStatus) {
        if (tenantId == null || llmUrl.isBlank()) {
            return;
        }
        boolean active = "active".equalsIgnoreCase(tenantStatus);
        try {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            h.set("X-Internal-Token", llmToken);
            java.util.HashMap<String, Object> body = new java.util.HashMap<>();
            body.put("tenantId", tenantId.toString());
            body.put("plan", planCode == null ? "" : planCode);
            body.put("subscriptionActive", active);
            body.put("env", billingEnv);
            users.findFirstByTenantIdAndTenantAdminTrue(tenantId)
                    .ifPresent(u -> body.put("userId", u.getId().toString()));
            http.postForEntity(
                    llmUrl.replaceAll("/$", "") + "/internal/billing/entitlements",
                    new HttpEntity<>(body, h),
                    Void.class);
        } catch (Exception e) {
            log.warn("widget entitlement sync failed tenant={}", tenantId);
        }
    }

    private boolean whatsappIncluded(String planCode) {
        String code = planCode == null ? "" : planCode.trim().toLowerCase(Locale.ROOT);
        if (WA_PLANS.contains(code)) {
            return true;
        }
        SubscriptionPlan plan = plans.findByCodeIgnoreCase(code).orElse(null);
        if (plan == null || plan.getFeatures() == null) {
            return false;
        }
        return Boolean.TRUE.equals(plan.getFeatures().get("whatsapp_included"));
    }
}
