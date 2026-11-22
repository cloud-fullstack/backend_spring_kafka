package com.omero.auth.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SectorWelcomeMail {

    private SectorWelcomeMail() {}

    static String html(WelcomeEmailI18n i18n, String vertical, Map<String, String> profile, String baseUrl) {
        Locale loc = localeOf(i18n);
        String sector = SectorOnboarding.sectorLabel(vertical, loc);
        int pro = SectorOnboarding.proMonthlyEur(vertical);
        SectorOnboarding.Addon addon = SectorOnboarding.teaserAddon(vertical);
        List<String> docs = SectorOnboarding.contractPaths(vertical, profile);
        String dash = baseUrl + "/dashboard";
        boolean it = i18n == WelcomeEmailI18n.IT;
        StringBuilder b = new StringBuilder();
        b.append("<p style=\"margin:0 0 12px 0\">");
        b.append(it ? "Settore: <strong>" : "Sector: <strong>").append(escape(sector)).append("</strong></p>");
        b.append("<p style=\"margin:0 0 12px 0\">");
        b.append(it
                ? "Il tuo piano Demo è attivo per " + SectorOnboarding.DEMO_TRIAL_DAYS + " giorni."
                : "Your Demo plan is active for " + SectorOnboarding.DEMO_TRIAL_DAYS + " days.");
        b.append("</p>");
        b.append("<p style=\"margin:0 0 8px 0\">");
        b.append(it ? "Quando vuoi passare a Pro:" : "When you want to move to Pro:");
        b.append("</p><ul style=\"margin:0 0 12px 18px;padding:0\">");
        b.append("<li>").append(it ? "Piano Pro: €" : "Pro plan: €").append(pro)
                .append(it ? "/mese (IVA esclusa)" : "/month (VAT excluded)").append("</li>");
        if (addon != null) {
            b.append("<li>").append(it ? "Integrazione opzionale: €" : "Optional integration: €")
                    .append(addon.priceEur()).append(it ? " una tantum (attivabile dopo)" : " one-off (activate later)")
                    .append("</li>");
        }
        b.append("</ul>");
        b.append("<p style=\"margin:0 0 12px 0\">");
        b.append(it ? "Gestisci tutto dalla tua area: " : "Manage everything from your workspace: ");
        b.append("<a href=\"").append(dash).append("\">").append(dash).append("</a></p>");
        b.append("<p style=\"margin:0 0 8px 0\">");
        b.append(it ? "Documenti inviati all’accettazione:" : "Documents sent on acceptance:");
        b.append("</p><ul style=\"margin:0 0 12px 18px;padding:0\">");
        for (String path : docs) {
            String url = baseUrl + path;
            b.append("<li><a href=\"").append(url).append("\">").append(url).append("</a></li>");
        }
        b.append("</ul>");
        return b.toString();
    }

    private static Locale localeOf(WelcomeEmailI18n i18n) {
        return switch (i18n) {
            case IT -> Locale.ITALIAN;
            case FR -> Locale.FRENCH;
            case DE -> Locale.GERMAN;
            case ES -> Locale.forLanguageTag("es");
            default -> Locale.ENGLISH;
        };
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
