package com.omero.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Data
@Entity
@Table(name = "tenant_addons")
@IdClass(TenantAddon.Key.class)
public class TenantAddon {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Id
    @Column(name = "addon_code")
    private String addonCode;

    private String status;

    @Column(nullable = false)
    private String source = "manual";

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
        if (status == null) {
            status = "active";
        }
        if (source == null || source.isBlank()) {
            source = "manual";
        }
    }

    @Data
    public static class Key implements Serializable {
        private UUID tenantId;
        private String addonCode;
    }
}
