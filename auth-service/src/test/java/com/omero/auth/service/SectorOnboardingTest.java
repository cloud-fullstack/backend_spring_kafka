package com.omero.auth.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectorOnboardingTest {

    @Test
    void medicalGetsArt9AndCalendarTeaser() {
        assertTrue(SectorOnboarding.needsArt9("medical", Map.of()));
        assertEquals(690, SectorOnboarding.teaserAddon("medical").priceEur());
        assertTrue(SectorOnboarding.contractPaths("medical", Map.of()).contains("/dpa#art9"));
    }

    @Test
    void hospitalityGetsIcalNotArt9() {
        assertEquals(490, SectorOnboarding.teaserAddon("hospitality").priceEur());
        assertEquals(2, SectorOnboarding.contractPaths("hospitality", Map.of()).size());
    }

    @Test
    void sanitizeDropsUnknownKeys() {
        String json = SectorOnboarding.toJson("hospitality", Map.of(
                "address", "Via Roma 1",
                "secret", "nope"));
        assertTrue(json.contains("Via Roma 1"));
        assertTrue(!json.contains("secret"));
    }
}
