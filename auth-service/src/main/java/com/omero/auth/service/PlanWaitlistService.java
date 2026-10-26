package com.omero.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.omero.auth.entity.PlanWaitlist;
import com.omero.auth.entity.SubscriptionPlan;
import com.omero.auth.repository.PlanWaitlistRepository;
import com.omero.auth.repository.SubscriptionPlanRepository;
import com.omero.auth.web.PlanNotAvailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class PlanWaitlistService {

    private static final int LIMIT = 8;

    private final PlanWaitlistRepository waitlist;
    private final SubscriptionPlanRepository plans;
    private final Cache<String, AtomicInteger> hits = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .maximumSize(20_000)
            .build();

    public PlanWaitlistService(PlanWaitlistRepository waitlist, SubscriptionPlanRepository plans) {
        this.waitlist = waitlist;
        this.plans = plans;
    }

    @Transactional
    public void join(String emailRaw, String planCodeRaw, UUID tenantId, HttpServletRequest request) {
        String ip = clientIp(request);
        int n = hits.get(ip, k -> new AtomicInteger()).incrementAndGet();
        if (n > LIMIT) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
        }
        String email = emailRaw == null ? "" : emailRaw.trim().toLowerCase(Locale.ROOT);
        String code = planCodeRaw == null ? "" : planCodeRaw.trim().toLowerCase(Locale.ROOT);
        if (email.isBlank() || !email.contains("@")) {
            throw new IllegalArgumentException("Invalid request");
        }
        SubscriptionPlan plan = plans.findByCodeIgnoreCase(code)
                .orElseThrow(() -> new IllegalArgumentException("Invalid request"));
        if (plan.catalogAvailable()) {
            throw new IllegalArgumentException("Invalid request");
        }
        if (!"coming_soon".equalsIgnoreCase(plan.featureStatus())) {
            throw new PlanNotAvailableException();
        }
        if (waitlist.existsByEmailIgnoreCaseAndPlanCodeIgnoreCase(email, plan.getCode())) {
            return;
        }
        PlanWaitlist row = new PlanWaitlist();
        row.setEmail(email);
        row.setPlanCode(plan.getCode());
        row.setTenantId(tenantId);
        waitlist.save(row);
    }

    private static String clientIp(HttpServletRequest request) {
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr() == null ? "anon" : request.getRemoteAddr();
    }
}
