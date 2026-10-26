package com.omero.auth.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "tenants")
public class Tenant {
    @Id
    @GeneratedValue
    private UUID id;

    private String name;
    private String email;
    private String status;

    @Column(name = "vertical")
    private String vertical;

    @Column(name = "company_name")
    private String companyName;

    @Column(name = "vat_number")
    private String vatNumber;

    private String country;

    @Column(name = "reseller_id")
    private UUID resellerId;

    @Column(name = "reseller_attributed_at")
    private OffsetDateTime resellerAttributedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @Column(name = "lemon_subscription_updated_at")
    private OffsetDateTime lemonSubscriptionUpdatedAt;

    @Column(name = "stripe_subscription_updated_at")
    private OffsetDateTime stripeSubscriptionUpdatedAt;

    @Column(name = "suspended_at")
    private OffsetDateTime suspendedAt;
}
