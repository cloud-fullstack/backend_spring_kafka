package com.omero.auth.scheduler;

import com.omero.auth.entity.Tenant;
import com.omero.auth.outbox.TenantOutbox;
import com.omero.auth.repository.TenantAddonRepository;
import com.omero.auth.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrialUpsellSchedulerTest {

    @Mock TenantOutbox outbox;
    @Mock TenantRepository tenants;
    @Mock TenantAddonRepository addons;

    @Test
    void sevenDayDemoSendsRedisEventOnce() {
        UUID id = UUID.randomUUID();
        Tenant t = new Tenant();
        t.setId(id);
        t.setName("Casa");
        t.setBusinessType("bnb");
        t.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(7).minusMinutes(10));
        when(tenants.findByStatusInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(List.of("demo", "registered")), any(), any()))
                .thenReturn(List.of(t));
        when(addons.existsByTenantIdAndAddonCodeAndStatusIgnoreCase(id, "ical_integration", "active"))
                .thenReturn(false);
        when(addons.existsByTenantIdAndAddonCodeAndStatusIgnoreCase(id, "ical_upsell_sent", "active"))
                .thenReturn(false);
        when(addons.findById(any())).thenReturn(Optional.empty());

        new TrialUpsellScheduler(outbox, tenants, addons).checkTrialWeekUpsell();

        verify(outbox).enqueueTrialWeek(eq(id), eq("bnb"), eq("Casa"), any());
        verify(addons).save(any());
    }

    @Test
    void skipsIfIcalAlreadyActive() {
        UUID id = UUID.randomUUID();
        Tenant t = new Tenant();
        t.setId(id);
        when(tenants.findByStatusInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(List.of("demo", "registered")), any(), any()))
                .thenReturn(List.of(t));
        when(addons.existsByTenantIdAndAddonCodeAndStatusIgnoreCase(id, "ical_integration", "active"))
                .thenReturn(true);

        new TrialUpsellScheduler(outbox, tenants, addons).checkTrialWeekUpsell();

        verify(outbox, never()).enqueueTrialWeek(any(), any(), any(), any());
    }
}
