package com.omero.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "stripe_subscriptions")
public class StripeSubscription {
    @Id
    @Column(name = "subscription_id")
    private String subscriptionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String status;

    @Column(name = "plan_code")
    private String planCode;

    @Column(name = "addon_codes")
    private String addonCodes;

    @Column(name = "event_at", nullable = false)
    private OffsetDateTime eventAt;

    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd;

    @Column(name = "cancel_at")
    private OffsetDateTime cancelAt;

    @Column(name = "period_end")
    private OffsetDateTime periodEnd;

    @Column(name = "canceled_at")
    private OffsetDateTime canceledAt;
}
