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
@Table(name = "whatsapp_accounts")
public class WhatsAppAccount {
    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "waba_id", nullable = false)
    private String wabaId;

    @Column(name = "phone_number_id", nullable = false)
    private String phoneNumberId;

    @Column(name = "display_number", nullable = false)
    private String displayNumber;

    @Column(name = "access_token_enc", nullable = false)
    private String accessTokenEnc;

    private String status;

    @Column(name = "connected_at")
    private OffsetDateTime connectedAt;

    @Column(name = "entitlement_off_at")
    private OffsetDateTime entitlementOffAt;
}
