package com.omero.auth.repository;

import com.omero.auth.entity.StripeInvoicePayment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StripeInvoicePaymentRepository extends JpaRepository<StripeInvoicePayment, String> {
    boolean existsByPaymentIntentId(String paymentIntentId);
}
