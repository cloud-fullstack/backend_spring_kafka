package com.omero.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerSignRequest {
    @JsonProperty("clauses_approved")
    @Builder.Default
    private Map<String, Object> clausesApproved = new LinkedHashMap<>();

    @Builder.Default
    private List<PartnerConsentDto> consents = new ArrayList<>();
}
