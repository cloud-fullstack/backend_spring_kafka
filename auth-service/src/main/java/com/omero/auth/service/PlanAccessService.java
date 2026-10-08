package com.omero.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.omero.auth.entity.Tenant;
import com.omero.auth.entity.User;
import com.omero.auth.plan.PlanFeatures;
import com.omero.auth.repository.TenantRepository;
import com.omero.auth.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class PlanAccessService {

    /**
     * Per-pod cache keyed by JWT uid. After payment or suspension, other replicas
     * see the change within expireAfterWrite (30 seconds). evict() only clears this pod.
     */
    private final Cache<UUID, Session> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;

    public PlanAccessService(UserRepository userRepository, TenantRepository tenantRepository) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
    }

    public record Session(
            boolean userFound,
            boolean active,
            String planId,
            String tenantStatus,
            boolean tenantLive
    ) {
        static Session missing() {
            return new Session(false, false, null, null, false);
        }
    }

    public record Entitlement(String planId, String tenantStatus) {}

    public Session resolve(UUID userId) {
        return cache.get(userId, this::load);
    }

    public Entitlement resolveByUserId(UUID userId) {
        Session s = resolve(userId);
        return new Entitlement(s.planId(), s.tenantStatus());
    }

    public boolean isActiveUser(UUID userId) {
        Session s = resolve(userId);
        return s.userFound() && s.active();
    }

    public void evict(UUID userId) {
        if (userId != null) {
            cache.invalidate(userId);
        }
    }

    public boolean hasFeature(UUID userId, String feature) {
        Session s = resolve(userId);
        if (!s.userFound() || !s.active() || !s.tenantLive()) {
            return false;
        }
        return PlanFeatures.allowed(s.planId(), feature);
    }

    public boolean isSubscriptionValid(UUID userId) {
        Session s = resolve(userId);
        return s.userFound() && s.active() && s.tenantLive();
    }

    private Session load(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return Session.missing();
        }
        String status = null;
        boolean tenantLive = false;
        if (user.getTenantId() != null) {
            Tenant tenant = tenantRepository.findById(user.getTenantId()).orElse(null);
            if (tenant != null && tenant.getStatus() != null) {
                status = tenant.getStatus();
                tenantLive = PlanFeatures.LIVE_TENANT.contains(status.toLowerCase());
            }
        }
        return new Session(true, user.isActive(), user.getPlanId(), status, tenantLive);
    }
}
