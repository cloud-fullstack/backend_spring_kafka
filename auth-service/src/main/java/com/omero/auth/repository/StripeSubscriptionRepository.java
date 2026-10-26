package com.omero.auth.repository;

import com.omero.auth.entity.StripeSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StripeSubscriptionRepository extends JpaRepository<StripeSubscription, String> {
    List<StripeSubscription> findByTenantId(UUID tenantId);
    boolean existsByTenantId(UUID tenantId);
}
