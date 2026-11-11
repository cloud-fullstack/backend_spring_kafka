package com.omero.auth.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WelcomeEmailI18nTest {

    @Test
    void fromCountryMapsKnownCodesAndDefaultsToEnglish() {
        assertEquals(WelcomeEmailI18n.EN, WelcomeEmailI18n.fromCountry(null));
        assertEquals(WelcomeEmailI18n.EN, WelcomeEmailI18n.fromCountry(""));
        assertEquals(WelcomeEmailI18n.IT, WelcomeEmailI18n.fromCountry("it"));
        assertEquals(WelcomeEmailI18n.FR, WelcomeEmailI18n.fromCountry("FR"));
        assertEquals(WelcomeEmailI18n.DE, WelcomeEmailI18n.fromCountry("AT"));
        assertEquals(WelcomeEmailI18n.ES, WelcomeEmailI18n.fromCountry("MX"));
        assertEquals(WelcomeEmailI18n.EN, WelcomeEmailI18n.fromCountry("GB"));
    }

    @Test
    void applyFillsPlaceholdersAndLoginUrl() {
        String html = WelcomeEmailI18n.IT.apply(
                "{{GREETING}}|{{BODY}}|{{CTA}}|{{LOGIN_URL}}|{{DISCLAIMER}}|{{SUPPORT_TEXT}}|{{RIGHTS}}",
                "https://omero.dev/login");
        assertTrue(html.startsWith("Benvenuto su Telemaco"));
        assertTrue(html.contains("https://omero.dev/login"));
        assertTrue(html.contains("Accedi al tuo account"));
        assertEquals("Benvenuto su Telemaco — registrazione completata", WelcomeEmailI18n.IT.subject());
    }
}
