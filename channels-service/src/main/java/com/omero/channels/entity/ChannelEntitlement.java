package com.omero.channels.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "channel_entitlements")
public class ChannelEntitlement {
    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    private boolean whatsapp;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
