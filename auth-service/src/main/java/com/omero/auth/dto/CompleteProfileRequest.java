package com.omero.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CompleteProfileRequest {
    @NotBlank
    private String vertical;

    @Size(max = 200)
    private String companyName;
}
