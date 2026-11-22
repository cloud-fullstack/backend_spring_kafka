package com.omero.auth.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtpEmailI18nTest {

    @Test
    void fromCountryFollowsWelcomeLocaleAndDefaultsToEnglish() {
        assertEquals(OtpEmailI18n.EN, OtpEmailI18n.fromCountry(null));
        assertEquals(OtpEmailI18n.EN, OtpEmailI18n.fromCountry(""));
        assertEquals(OtpEmailI18n.IT, OtpEmailI18n.fromCountry("IT"));
        assertEquals(OtpEmailI18n.FR, OtpEmailI18n.fromCountry("fr"));
        assertEquals(OtpEmailI18n.DE, OtpEmailI18n.fromCountry("AT"));
        assertEquals(OtpEmailI18n.ES, OtpEmailI18n.fromCountry("MX"));
        assertEquals(OtpEmailI18n.EN, OtpEmailI18n.fromCountry("GB"));
    }

    @Test
    void applyFillsCodeAndExpiry() {
        Instant expires = Instant.parse("2026-11-21T12:00:00Z");
        String html = OtpEmailI18n.IT.apply(
                "{{HEADING}}|{{CODE}}|{{INTRO}}|{{FRONTEND_BASE_URL}}",
                "123456",
                expires,
                10,
                "https://telemaco.app");
        assertTrue(html.contains("123456"));
        assertTrue(html.contains("10"));
        assertTrue(html.contains("https://telemaco.app"));
        assertEquals("Il tuo codice di verifica Telemaco", OtpEmailI18n.IT.subject());
    }
}
