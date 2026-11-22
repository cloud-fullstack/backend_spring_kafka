package com.omero.auth.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "tenants")
public class Tenant {
    @Id
    @GeneratedValue
    private UUID id;

    private String name;
    private String email;
    private String status;

    @Column(name = "vertical")
    private String vertical;

    /** LLM handler code ({@code business_types}): doctor, lab, lawyer, pizzeria, bnb, ecommerce, vehicle. */
    @Column(name = "business_type")
    private String businessType;

    @Column(name = "company_name")
    private String companyName;

    /**
     * Inbox for booking/reservation alerts for the chosen service.
     * Null or blank → fall back to {@link #email} (registration address).
     */
    @Column(name = "booking_notification_email")
    private String bookingNotificationEmail;

    /** Sector fields collected at signup (JSON). Integration secrets are stored later. */
    @Column(name = "onboarding_profile")
    private String onboardingProfile;

    @Column(name = "vat_number")
    private String vatNumber;

    private String country;

    @Column(name = "reseller_id")
    private UUID resellerId;

    @Column(name = "reseller_attributed_at")
    private OffsetDateTime resellerAttributedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @Column(name = "lemon_subscription_updated_at")
    private OffsetDateTime lemonSubscriptionUpdatedAt;

    @Column(name = "stripe_subscription_updated_at")
    private OffsetDateTime stripeSubscriptionUpdatedAt;

    @Column(name = "suspended_at")
    private OffsetDateTime suspendedAt;

    /** Demo clock. Reminders: scheduled send time; null after the mail went out. */
    @Column(name = "trial_started_at")
    private OffsetDateTime trialStartedAt;

    @Column(name = "trial_days")
    private Integer trialDays;

    @Column(name = "trial_reminder_3d_at")
    private OffsetDateTime trialReminder3dAt;

    @Column(name = "trial_reminder_1d_at")
    private OffsetDateTime trialReminder1dAt;

    @Column(name = "trial_reminder_0d_at")
    private OffsetDateTime trialReminder0dAt;
}
