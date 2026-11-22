package com.omero.auth.repository;

import com.omero.auth.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    List<Tenant> findByStatusAndSuspendedAtBefore(String status, OffsetDateTime cutoff);

    List<Tenant> findByStatusInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            List<String> statuses, OffsetDateTime fromInclusive, OffsetDateTime toExclusive);

    @Query("""
            select t from Tenant t
             where t.status in :statuses
               and t.trialReminder3dAt is not null
               and t.trialReminder3dAt <= :now
            """)
    List<Tenant> dueTrialReminder3d(@Param("statuses") List<String> statuses, @Param("now") OffsetDateTime now);

    @Query("""
            select t from Tenant t
             where t.status in :statuses
               and t.trialReminder1dAt is not null
               and t.trialReminder1dAt <= :now
            """)
    List<Tenant> dueTrialReminder1d(@Param("statuses") List<String> statuses, @Param("now") OffsetDateTime now);

    @Query("""
            select t from Tenant t
             where t.status in :statuses
               and t.trialReminder0dAt is not null
               and t.trialReminder0dAt <= :now
            """)
    List<Tenant> dueTrialReminder0d(@Param("statuses") List<String> statuses, @Param("now") OffsetDateTime now);
}
