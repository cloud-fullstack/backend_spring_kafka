package com.omero.auth.service;

public enum TrialReminderI18n {
    D3, D1, D0;

    String subject(WelcomeEmailI18n lang) {
        WelcomeEmailI18n L = lang == null ? WelcomeEmailI18n.EN : lang;
        return switch (this) {
            case D3 -> switch (L) {
                case IT -> "La tua prova Telemaco scade tra 3 giorni";
                case FR -> "Votre essai Telemaco se termine dans 3 jours";
                case DE -> "Ihre Telemaco-Testphase endet in 3 Tagen";
                case ES -> "Tu prueba Telemaco termina en 3 días";
                case EN -> "Your Telemaco trial ends in 3 days";
            };
            case D1 -> switch (L) {
                case IT -> "La tua prova Telemaco scade domani";
                case FR -> "Votre essai Telemaco se termine demain";
                case DE -> "Ihre Telemaco-Testphase endet morgen";
                case ES -> "Tu prueba Telemaco termina mañana";
                case EN -> "Your Telemaco trial ends tomorrow";
            };
            case D0 -> switch (L) {
                case IT -> "La prova Telemaco è scaduta";
                case FR -> "Votre essai Telemaco est terminé";
                case DE -> "Ihre Telemaco-Testphase ist beendet";
                case ES -> "Tu prueba Telemaco ha terminado";
                case EN -> "Your Telemaco trial has ended";
            };
        };
    }

    String bodyHtml(WelcomeEmailI18n lang, String dashboardUrl) {
        WelcomeEmailI18n L = lang == null ? WelcomeEmailI18n.EN : lang;
        String lead = switch (this) {
            case D3 -> switch (L) {
                case IT -> "Restano 3 giorni di piano Demo. Passa a Pro per non interrompere l’assistente.";
                case FR -> "Il reste 3 jours de plan Démo. Passez à Pro pour que l’assistant continue.";
                case DE -> "Noch 3 Tage Demo. Wechseln Sie zu Pro, damit der Assistent weiterantwortet.";
                case ES -> "Quedan 3 días de plan Demo. Pasa a Pro para no interrumpir el asistente.";
                case EN -> "3 days left on the Demo plan. Move to Pro so the assistant keeps answering.";
            };
            case D1 -> switch (L) {
                case IT -> "Domani la prova finisce. Scegli un piano in area clienti.";
                case FR -> "L’essai se termine demain. Choisissez un plan dans l’espace client.";
                case DE -> "Die Testphase endet morgen. Wählen Sie einen Plan im Kundenbereich.";
                case ES -> "La prueba termina mañana. Elige un plan en el área de clientes.";
                case EN -> "The trial ends tomorrow. Choose a plan in your workspace.";
            };
            case D0 -> switch (L) {
                case IT -> "La prova è conclusa. L’assistente sul sito resta fermo finché non attivi un piano.";
                case FR -> "L’essai est terminé. L’assistant du site reste inactif jusqu’à l’activation d’un plan.";
                case DE -> "Die Testphase ist beendet. Der Assistent bleibt aus, bis Sie einen Plan aktivieren.";
                case ES -> "La prueba ha terminado. El asistente del sitio permanece apagado hasta que actives un plan.";
                case EN -> "The trial has ended. The site assistant stays off until you activate a plan.";
            };
        };
        String cta = switch (L) {
            case IT -> "Apri l’area Piano";
            case FR -> "Ouvrir l’onglet Piano";
            case DE -> "Piano-Bereich öffnen";
            case ES -> "Abrir la pestaña Piano";
            case EN -> "Open the Piano tab";
        };
        return "<p>" + lead + "</p><p><a href=\"" + dashboardUrl + "\">" + cta + "</a></p>";
    }
}
