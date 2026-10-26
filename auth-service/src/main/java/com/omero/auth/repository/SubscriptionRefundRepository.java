package com.omero.auth.repository;

import com.omero.auth.entity.SubscriptionRefund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SubscriptionRefundRepository extends JpaRepository<SubscriptionRefund, UUID> {
    boolean existsByTenantIdAndCreatedAtAfterAndStatusIn(UUID tenantId, OffsetDateTime after, Collection<String> statuses);
    boolean existsByTenantIdAndCreatedAtAfterAndStatusInAndAmountCentsGreaterThan(
            UUID tenantId, OffsetDateTime after, Collection<String> statuses, long amountCents);
    boolean existsByPaymentIntentId(String paymentIntentId);
    List<SubscriptionRefund> findByStatusInAndUpdatedAtBefore(Collection<String> statuses, OffsetDateTime before);
}
