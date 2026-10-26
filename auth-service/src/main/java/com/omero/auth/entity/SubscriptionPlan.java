package com.omero.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Map;
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

    @Column(name = "sort_order")
    private Integer sortOrder;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> features;

    @Column(name = "lemon_variant_id", unique = true)
    private String lemonVariantId;

    @Column(name = "stripe_price_id", unique = true)
    private String stripePriceId;

    @Column(name = "reseller_commission_percent")
    private BigDecimal resellerCommissionPercent;

    public String featureStatus() {
        if (features == null || features.get("status") == null) {
            return "";
        }
        return features.get("status").toString();
    }

    public boolean catalogAvailable() {
        return "available".equalsIgnoreCase(featureStatus());
    }

    public boolean isAddon() {
        if (features == null || features.get("kind") == null) {
            return false;
        }
        return "addon".equalsIgnoreCase(features.get("kind").toString());
    }

    public java.util.List<String> requirementCodes() {
        if (features == null || features.get("requires") == null) {
            return java.util.List.of();
        }
        Object raw = features.get("requires");
        if (!(raw instanceof java.util.Collection<?> col)) {
            return java.util.List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Object o : col) {
            if (o != null && !o.toString().isBlank()) {
                out.add(o.toString().trim().toLowerCase());
            }
        }
        return out;
    }
}
