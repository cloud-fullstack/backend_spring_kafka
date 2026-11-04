package com.omero.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerConsentDto {
    private String purpose;
    private boolean granted;

    @JsonProperty("clause_text")
    private String clauseText;

    @JsonProperty("document_version")
    private String documentVersion;
}
