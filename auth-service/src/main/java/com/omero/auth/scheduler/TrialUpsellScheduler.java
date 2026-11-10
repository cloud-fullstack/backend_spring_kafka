package com.omero.auth.scheduler;

import com.omero.auth.entity.Tenant;
import com.omero.auth.entity.TenantAddon;
import com.omero.auth.outbox.TenantOutbox;
import com.omero.auth.repository.TenantAddonRepository;
import com.omero.auth.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Eventi ciclo di vita demo → outbox (poi Redis Streams).
 * Pagamenti: solo Stripe webhook / SubscriptionStateService, non endpoint finti.
 */
@Component
public class TrialUpsellScheduler {

    static final String UPSELL_SENT = "ical_upsell_sent";
    static final String ICAL = "ical_integration";

    private static final Logger log = LoggerFactory.getLogger(TrialUpsellScheduler.class);

    private final TenantOutbox outbox;
    private final TenantRepository tenants;
    private final TenantAddonRepository addons;

    public TrialUpsellScheduler(
            TenantOutbox outbox,
            TenantRepository tenants,
            TenantAddonRepository addons) {
        this.outbox = outbox;
        this.tenants = tenants;
        this.addons = addons;
    }

    @Scheduled(cron = "0 5 * * * *")
    @Transactional
    public void checkTrialWeekUpsell() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime sevenDaysAgo = now.minusDays(7);
        OffsetDateTime eightDaysAgo = now.minusDays(8);
        List<Tenant> candidates = tenants.findByStatusInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                List.of("demo", "registered"), eightDaysAgo, sevenDaysAgo);
        for (Tenant t : candidates) {
            if (t.getId() == null) {
                continue;
            }
            if (addons.existsByTenantIdAndAddonCodeAndStatusIgnoreCase(t.getId(), ICAL, "active")) {
                continue;
            }
            if (addons.existsByTenantIdAndAddonCodeAndStatusIgnoreCase(t.getId(), UPSELL_SENT, "active")) {
                continue;
            }
            outbox.enqueueTrialWeek(
                    t.getId(),
                    t.getBusinessType() != null ? t.getBusinessType() : t.getVertical(),
                    t.getName(),
                    t.getCreatedAt() == null ? now.toInstant() : t.getCreatedAt().toInstant());
            markAddon(t.getId(), UPSELL_SENT);
            log.info("upsell trigger outbox: tenant={} sector={}", t.getId(), t.getVertical());
        }
    }

    /** Dopo pagamento Stripe addon iCal (ChannelEntitlementClient.activateAddon). */
    public void activateIcalIntegration(UUID tenantId) {
        outbox.enqueueFeature(tenantId, ICAL, true);
        log.info("ical_integration outbox: tenant={}", tenantId);
    }

    private void markAddon(UUID tenantId, String code) {
        TenantAddon.Key key = new TenantAddon.Key();
        key.setTenantId(tenantId);
        key.setAddonCode(code);
        if (addons.findById(key).isPresent()) {
            return;
        }
        TenantAddon row = new TenantAddon();
        row.setTenantId(tenantId);
        row.setAddonCode(code);
        row.setStatus("active");
        row.setSource("scheduler");
        addons.save(row);
    }
}
