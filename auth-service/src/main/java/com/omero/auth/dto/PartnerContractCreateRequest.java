package com.omero.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerContractCreateRequest {
    /** CUSTOM | ZONE | CODE | DPA */
    @NotBlank
    private String type;
}
