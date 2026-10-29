package com.omero.auth.repository;

import com.omero.auth.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    List<Tenant> findByStatusAndSuspendedAtBefore(String status, OffsetDateTime cutoff);
}
