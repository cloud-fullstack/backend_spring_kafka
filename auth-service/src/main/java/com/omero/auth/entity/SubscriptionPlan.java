package com.omero.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Entity
@Table(name = "subscription_plans")
public class SubscriptionPlan {
    @Id
    private UUID id;
    private String code;
    private String name;
    private BigDecimal price;

    @Column(name = "lemon_variant_id", unique = true)
    private String lemonVariantId;

    @Column(name = "reseller_commission_percent")
    private BigDecimal resellerCommissionPercent;
}
