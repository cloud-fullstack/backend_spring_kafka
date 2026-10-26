package com.omero.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "billing_accounts")
public class BillingAccount {
    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "balance_cents", nullable = false)
    private long balanceCents;

    @Column(name = "spend_month_cents", nullable = false)
    private long spendMonthCents;

    @Column(name = "spend_month", length = 7)
    private String spendMonth;

    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Column(name = "auto_recharge", nullable = false)
    private boolean autoRecharge;

    @Column(name = "auto_recharge_threshold_cents")
    private Long autoRechargeThresholdCents;

    @Column(name = "auto_recharge_amount_cents")
    private Long autoRechargeAmountCents;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
