package com.omero.auth.repository;

import com.omero.auth.entity.BillingAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface BillingAccountRepository extends JpaRepository<BillingAccount, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE BillingAccount a SET a.balanceCents = a.balanceCents + :cents, a.updatedAt = CURRENT_TIMESTAMP WHERE a.tenantId = :tenantId")
    int addBalance(@Param("tenantId") UUID tenantId, @Param("cents") long cents);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BillingAccount a SET a.stripeCustomerId = :customerId, a.updatedAt = CURRENT_TIMESTAMP
            WHERE a.tenantId = :tenantId
              AND (a.stripeCustomerId IS NULL OR a.stripeCustomerId = '')
            """)
    int setStripeCustomerIfAbsent(@Param("tenantId") UUID tenantId, @Param("customerId") String customerId);
}
