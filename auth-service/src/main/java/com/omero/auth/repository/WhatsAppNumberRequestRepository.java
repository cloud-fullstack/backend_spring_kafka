package com.omero.auth.repository;

import com.omero.auth.entity.WhatsAppNumberRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface WhatsAppNumberRequestRepository extends JpaRepository<WhatsAppNumberRequest, UUID> {

    boolean existsByTenantIdAndStatusIn(UUID tenantId, Collection<String> statuses);

    Optional<WhatsAppNumberRequest> findByStripeSessionId(String stripeSessionId);
}
