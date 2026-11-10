package com.omero.auth.service;

public record JollySetupRequest(
        boolean hasBusinessNumber,
        String companyName,
        String vatNumber,
        String address,
        String areaCode,
        String displayName,
        boolean termsAccepted
) {}
