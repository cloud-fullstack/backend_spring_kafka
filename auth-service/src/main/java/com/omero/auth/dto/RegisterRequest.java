package com.omero.auth.dto;

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

    @Size(max = 20)
    private String vatNumber;

    @Pattern(regexp = "^[A-Za-z]{2}$")
    private String country;

    @Size(max = 20)
    private String referralCode;
}
