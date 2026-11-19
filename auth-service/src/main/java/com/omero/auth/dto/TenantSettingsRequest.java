package com.omero.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TenantSettingsRequest {
    /**
     * Inbox for booking alerts. Empty string clears the override (use registration email).
     */
    @Email
    @Size(max = 254)
    private String bookingNotificationEmail;
}
