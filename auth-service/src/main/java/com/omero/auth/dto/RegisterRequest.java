package com.omero.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {
    private String firstName;
    private String lastName;

    @NotBlank
    @Email
    @Size(max = 254)
    private String email;

    @NotBlank
    @Size(min = 6, max = 128)
    private String password;

    @NotBlank
    private String confirmPassword;

    @NotBlank
    private String vertical;

    @Size(max = 200)
    private String companyName;

    /**
     * Optional inbox for service bookings (BnB, rental, medical, …).
     * Empty → use registration {@link #email}.
     */
    @Email
    @Size(max = 254)
    private String bookingNotificationEmail;

    @Size(max = 20)
    private String vatNumber;

    @Pattern(regexp = "^[A-Za-z]{2}$")
    private String country;

    @Size(max = 20)
    private String referralCode;

    @AssertTrue
    private boolean dpaAccepted;

    /** Cloudflare Turnstile token from the SPA. Optional until siteverify is wired. */
    @Size(max = 2048)
    private String turnstileToken;
}
