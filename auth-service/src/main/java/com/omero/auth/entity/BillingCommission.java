package com.omero.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "billing_commissions")
public class BillingCommission {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "reseller_id", nullable = false)
    private UUID resellerId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "plan_code")
    private String planCode;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "commission_percent", nullable = false)
    private BigDecimal commissionPercent;

    @Column(name = "lemon_order_id", unique = true)
    private String lemonOrderId;

    private String status = "pending";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
