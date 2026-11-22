package com.omero.auth.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TrialReminderI18nTest {

    @Test
    void italianSubjectsMatchRemainingDays() {
        assertTrue(TrialReminderI18n.D3.subject(WelcomeEmailI18n.IT).contains("3"));
        assertTrue(TrialReminderI18n.D1.subject(WelcomeEmailI18n.IT).toLowerCase().contains("domani"));
        assertTrue(TrialReminderI18n.D0.bodyHtml(WelcomeEmailI18n.EN, "https://telemaco.app/dashboard")
                .contains("https://telemaco.app/dashboard"));
        assertTrue(TrialReminderI18n.D3.subject(WelcomeEmailI18n.FR).contains("Telemaco"));
        assertTrue(TrialReminderI18n.D1.subject(WelcomeEmailI18n.DE).toLowerCase().contains("morgen"));
        assertTrue(TrialReminderI18n.D0.subject(WelcomeEmailI18n.ES).toLowerCase().contains("telemaco"));
    }
}
