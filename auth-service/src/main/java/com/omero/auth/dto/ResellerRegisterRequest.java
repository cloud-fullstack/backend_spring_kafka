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
public class ResellerRegisterRequest {
    private String firstName;
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
    private String phone;

    @NotBlank
    @Size(max = 200)
    private String companyName;

    @Size(max = 20)
    private String vatNumber;

    @Pattern(regexp = "^[A-Za-z]{2}$")
    private String country;

    private String website;
    private String[] servicesOffered;
    private String customizationNeeds;
    private String additionalComments;

    @AssertTrue
    private boolean agreedToTerms;
}
