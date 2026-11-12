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

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerApplicationRequest {
    @NotBlank
    @Size(max = 80)
    private String firstName;

    @NotBlank
    @Size(max = 80)
    private String lastName;

    @NotBlank
    @Email
    @Size(max = 254)
    private String email;

    @NotBlank
    @Size(min = 10, max = 128)
    private String password;

    @NotBlank
    private String confirmPassword;

    @NotBlank
    @Size(max = 40)
    private String phone;

    @NotBlank
    @Size(max = 200)
    private String legalName;

    @NotBlank
    @Pattern(regexp = "^[A-Za-z]{2}$")
    private String country;

    @NotBlank
    @Size(max = 20)
    private String vatNumber;

    @Size(max = 200)
    private String activity;

    @NotBlank
    @Size(min = 50, max = 4000)
    private String intro;

    /** CUSTOM | ZONE | CODE — un solo valore, CHECK su resellers.interest. */
    @NotBlank
    private String interest;

    @AssertTrue
    private boolean accountTerms;

    @AssertTrue
    private boolean ratesSummary;

    @AssertTrue
    private boolean dpaAnnex3;

    private boolean marketing;
    private boolean profiling;

    @Builder.Default
    private List<PartnerConsentDto> consents = new ArrayList<>();
}
