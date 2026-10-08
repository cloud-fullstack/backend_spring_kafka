package com.omero.auth.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "plan_id", nullable = false)
    private String planId = "demo";

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "business_name")
    private String businessName;

    @Column(name = "is_active")
    private boolean active = true;

    @Column(name = "is_reseller")
    private boolean reseller = false;

    @Column(name = "is_tenant_admin")
    private boolean tenantAdmin = true;

    @Column(name = "google_id")
    private String googleId;

    @Column(name = "facebook_id")
    private String facebookId;

    @Column(name = "reset_token")
    private String resetToken;

    @Column(name = "reset_token_expires")
    private OffsetDateTime resetTokenExpires;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
