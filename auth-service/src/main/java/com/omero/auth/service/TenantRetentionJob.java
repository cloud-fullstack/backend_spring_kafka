package com.omero.auth.service;

import com.omero.auth.entity.Tenant;
import com.omero.auth.entity.User;
import com.omero.auth.repository.TenantRepository;
import com.omero.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class TenantRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(TenantRetentionJob.class);

    private final TenantRepository tenants;
    private final UserRepository users;
    private final ChannelEntitlementClient operational;

    public TenantRetentionJob(
            TenantRepository tenants,
            UserRepository users,
            ChannelEntitlementClient operational) {
        this.tenants = tenants;
        this.users = users;
        this.operational = operational;
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "Europe/Rome")
    @Transactional
    public void purgeExpired() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(90);
        for (Tenant tenant : tenants.findByStatusAndSuspendedAtBefore("suspended", cutoff)) {
            if (alreadyPurged(tenant)) {
                continue;
            }
            purge(tenant);
        }
    }

    void purge(Tenant tenant) {
        UUID id = tenant.getId();
        for (User user : users.findByTenantId(id)) {
            if (user.isReseller()) {
                continue;
            }
            user.setEmail("deleted-" + user.getId() + "@invalid.local");
            user.setPasswordHash(null);
            user.setFirstName(null);
            user.setLastName(null);
            user.setPhoneNumber(null);
            user.setBusinessName(null);
            user.setGoogleId(null);
            user.setFacebookId(null);
            user.setResetToken(null);
            user.setResetTokenExpires(null);
            user.setActive(false);
            users.save(user);
        }
        tenant.setEmail(null);
        tenant.setName("deleted");
        tenant.setCompanyName(null);
        tenant.setVatNumber(null);
        tenants.save(tenant);
        operational.purgeOperationalData(id);
        log.info("purged tenant {} after 90-day retention", id);
    }

    static boolean alreadyPurged(Tenant tenant) {
        return "deleted".equalsIgnoreCase(tenant.getName()) && tenant.getEmail() == null;
    }
}
