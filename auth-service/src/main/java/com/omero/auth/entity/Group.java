package com.omero.auth.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Data
@Entity
@Table(name = "groups")
public class Group {
    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String type; // e.g., "MEDICAL_CENTER", "CLINIC"

    @Column(nullable = false)
    private String planName;

    @Column(nullable = false)
    private LocalDateTime subscriptionStartDate;

    @Column(nullable = false)
    private LocalDateTime subscriptionEndDate;

    @Column(nullable = false)
    private boolean isActive;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "group_authorities", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "authority")
    private Set<String> authorities;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @ManyToMany(mappedBy = "groups")
    private Set<User> users;
}
