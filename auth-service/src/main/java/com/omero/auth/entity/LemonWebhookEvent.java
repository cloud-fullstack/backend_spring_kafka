package com.omero.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "lemon_webhook_events")
public class LemonWebhookEvent {
    @Id
    @Column(name = "event_id")
    private String eventId;

    @Column(name = "event_name", nullable = false)
    private String eventName;

    @CreationTimestamp
    @Column(name = "received_at", updatable = false)
    private OffsetDateTime receivedAt;
}
