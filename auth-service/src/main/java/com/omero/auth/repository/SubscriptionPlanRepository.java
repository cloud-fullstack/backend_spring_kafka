package com.omero.auth.repository;

import com.omero.auth.entity.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, UUID> {
    Optional<SubscriptionPlan> findByCodeIgnoreCase(String code);
    Optional<SubscriptionPlan> findByLemonVariantId(String lemonVariantId);
}
