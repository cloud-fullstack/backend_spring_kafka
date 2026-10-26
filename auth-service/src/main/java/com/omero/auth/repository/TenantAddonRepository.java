package com.omero.auth.repository;

import com.omero.auth.entity.TenantAddon;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TenantAddonRepository extends JpaRepository<TenantAddon, TenantAddon.Key> {
    boolean existsByTenantIdAndAddonCodeAndStatusIgnoreCase(UUID tenantId, String addonCode, String status);
    List<TenantAddon> findByTenantId(UUID tenantId);
}
