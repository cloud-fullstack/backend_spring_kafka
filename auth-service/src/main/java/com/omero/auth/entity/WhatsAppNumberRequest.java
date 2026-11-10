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
@Table(name = "whatsapp_number_requests")
public class WhatsAppNumberRequest {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "stripe_session_id")
    private String stripeSessionId;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(name = "vat_number")
    private String vatNumber;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String address;

    @Column(name = "area_code")
    private String areaCode;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "terms_accepted", nullable = false)
    private boolean termsAccepted;

    @Column(nullable = false)
    private String status = "pending_payment";

    @Column(name = "phone_number")
    private String phoneNumber;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
