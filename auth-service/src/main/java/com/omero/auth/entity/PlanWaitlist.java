package com.omero.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.PrePersist;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "plan_waitlist", uniqueConstraints = @UniqueConstraint(columnNames = {"email", "plan_code"}))
public class PlanWaitlist {
    @Id
    private UUID id;

    @PrePersist
    void ensureId() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    @Column(nullable = false)
    private String email;

    @Column(name = "plan_code", nullable = false, length = 50)
    private String planCode;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
