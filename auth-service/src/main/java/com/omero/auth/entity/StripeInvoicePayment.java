package com.omero.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.UUID;

@Data
@Entity
@Table(name = "stripe_invoice_payments")
public class StripeInvoicePayment {

    @Id
    @Column(name = "payment_intent_id")
    private String paymentIntentId;

    @Column(name = "invoice_id", nullable = false)
    private String invoiceId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;
}
