package com.omero.channels.repository;

import com.omero.channels.entity.WhatsAppOnboarding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WhatsAppOnboardingRepository extends JpaRepository<WhatsAppOnboarding, UUID> {
    Optional<WhatsAppOnboarding> findFirstByTenantIdAndStatusNotIn(UUID tenantId, List<String> statuses);

    Optional<WhatsAppOnboarding> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<WhatsAppOnboarding> findFirstByTenantIdOrderByUpdatedAtDesc(UUID tenantId);
}
