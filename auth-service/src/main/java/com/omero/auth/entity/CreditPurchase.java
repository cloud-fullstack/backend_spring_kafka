package com.omero.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "credit_purchases")
public class CreditPurchase {
    @Id
    @Column(name = "checkout_session_id")
    private String checkoutSessionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "payment_intent_id", unique = true)
    private String paymentIntentId;

    @Column(name = "credit_cents", nullable = false)
    private long creditCents;

    @Column(name = "amount_total_cents", nullable = false)
    private long amountTotalCents;

    @Column(name = "reversed_cents", nullable = false)
    private long reversedCents;

    @Column(name = "status", nullable = false)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
