package com.omero.channels.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Data
@Entity
@Table(name = "whatsapp_onboarding")
public class WhatsAppOnboarding {
    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    private String mode;

    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    private String category;

    @Column(name = "contact_email", nullable = false)
    private String contactEmail;

    @Column(name = "backup_ack", nullable = false)
    private boolean backupAck;

    @Column(name = "not_on_wa_ack", nullable = false)
    private boolean notOnWaAck;

    private String status;

    @Column(name = "error_reason")
    private String errorReason;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = "draft";
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
