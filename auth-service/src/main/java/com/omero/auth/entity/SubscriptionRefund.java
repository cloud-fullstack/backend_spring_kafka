package com.omero.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "subscription_refunds")
public class SubscriptionRefund {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "subscription_id", nullable = false, unique = true)
    private String subscriptionId;

    @Column(name = "invoice_id", nullable = false)
    private String invoiceId;

    @Column(name = "payment_intent_id", nullable = false)
    private String paymentIntentId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(name = "stripe_refund_id")
    private String stripeRefundId;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int attempts;

    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
