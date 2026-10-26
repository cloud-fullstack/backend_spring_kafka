package com.omero.channels.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "whatsapp_webhook_events")
public class WhatsAppWebhookEvent {
    @Id
    @Column(name = "message_id")
    private String messageId;

    @Column(name = "received_at")
    private OffsetDateTime receivedAt;
}
