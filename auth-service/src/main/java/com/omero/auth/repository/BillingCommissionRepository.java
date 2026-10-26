package com.omero.auth.repository;

import com.omero.auth.entity.BillingCommission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BillingCommissionRepository extends JpaRepository<BillingCommission, UUID> {
    boolean existsByLemonOrderId(String lemonOrderId);
    boolean existsByStripeInvoiceId(String stripeInvoiceId);
    java.util.Optional<BillingCommission> findByStripePaymentIntentId(String stripePaymentIntentId);
}
