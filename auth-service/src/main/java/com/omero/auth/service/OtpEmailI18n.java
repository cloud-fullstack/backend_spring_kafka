package com.omero.auth.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public enum OtpEmailI18n {

    IT("it", "Europe/Rome",
            "Verifica account",
            "Il tuo codice di accesso",
            "Usa questo codice per completare la verifica del tuo account Telemaco. Scade tra {minutes} minuti.",
            "Codice di verifica",
            "Il codice scade alle {time}. Se non hai richiesto questo codice, ignora questa email.",
            "Telemaco non ti chiederà mai questo codice via telefono o chat. Non condividerlo con nessuno.",
            "Hai ricevuto questa email perché qualcuno ha tentato di registrarsi su telemaco.app con questo indirizzo.",
            "Privacy", "Termini"),

    EN("en", "UTC",
            "Account verification",
            "Your access code",
            "Use this code to verify your Telemaco account. It expires in {minutes} minutes.",
            "Verification code",
            "The code expires at {time}. If you did not request this code, ignore this email.",
            "Telemaco will never ask for this code by phone or chat. Do not share it.",
            "You received this email because someone tried to register on telemaco.app with this address.",
            "Privacy", "Terms"),

    FR("fr", "Europe/Paris",
            "Vérification du compte",
            "Votre code d'accès",
            "Utilisez ce code pour vérifier votre compte Telemaco. Il expire dans {minutes} minutes.",
            "Code de vérification",
            "Le code expire à {time}. Si vous n'avez pas demandé ce code, ignorez cet e-mail.",
            "Telemaco ne vous demandera jamais ce code par téléphone ou chat. Ne le partagez pas.",
            "Vous recevez cet e-mail parce que quelqu'un a tenté de s'inscrire sur telemaco.app avec cette adresse.",
            "Confidentialité", "Conditions"),

    DE("de", "Europe/Berlin",
            "Kontobestätigung",
            "Ihr Zugangscode",
            "Verwenden Sie diesen Code, um Ihr Telemaco-Konto zu bestätigen. Er läuft in {minutes} Minuten ab.",
            "Bestätigungscode",
            "Der Code läuft um {time} ab. Wenn Sie diesen Code nicht angefordert haben, ignorieren Sie diese E-Mail.",
            "Telemaco wird diesen Code niemals per Telefon oder Chat erfragen. Teilen Sie ihn nicht.",
            "Sie erhalten diese E-Mail, weil jemand versucht hat, sich auf telemaco.app mit dieser Adresse zu registrieren.",
            "Datenschutz", "Nutzungsbedingungen"),

    ES("es", "Europe/Madrid",
            "Verificación de cuenta",
            "Tu código de acceso",
            "Usa este código para verificar tu cuenta Telemaco. Caduca en {minutes} minutos.",
            "Código de verificación",
            "El código caduca a las {time}. Si no lo has solicitado, ignora este correo.",
            "Telemaco nunca te pedirá este código por teléfono o chat. No lo compartas.",
            "Has recibido este correo porque alguien ha intentado registrarse en telemaco.app con esta dirección.",
            "Privacidad", "Términos");

    public final String lang;
    public final String zone;
    public final String badge;
    public final String heading;
    public final String intro;
    public final String codeLabel;
    public final String expiryNote;
    public final String security;
    public final String footer;
    public final String privacy;
    public final String terms;

    OtpEmailI18n(String lang, String zone, String badge, String heading, String intro, String codeLabel,
                 String expiryNote, String security, String footer, String privacy, String terms) {
        this.lang = lang;
        this.zone = zone;
        this.badge = badge;
        this.heading = heading;
        this.intro = intro;
        this.codeLabel = codeLabel;
        this.expiryNote = expiryNote;
        this.security = security;
        this.footer = footer;
        this.privacy = privacy;
        this.terms = terms;
    }

    public String subject() {
        return switch (this) {
            case IT -> "Il tuo codice di verifica Telemaco";
            case EN -> "Your Telemaco verification code";
            case FR -> "Votre code de vérification Telemaco";
            case DE -> "Ihr Telemaco-Bestätigungscode";
            case ES -> "Tu código de verificación Telemaco";
        };
    }

    public static OtpEmailI18n fromCountry(String country) {
        return switch (WelcomeEmailI18n.fromCountry(country)) {
            case IT -> IT;
            case FR -> FR;
            case DE -> DE;
            case ES -> ES;
            case EN -> EN;
        };
    }

    public String apply(String html, String code, Instant expires, long minutes, String siteBase) {
        String time = DateTimeFormatter.ofPattern("HH:mm")
                .withLocale(Locale.forLanguageTag(lang))
                .withZone(ZoneId.of(zone))
                .format(expires);
        String base = siteBase.endsWith("/") ? siteBase.substring(0, siteBase.length() - 1) : siteBase;
        return html
                .replace("{{LANG}}", lang)
                .replace("{{SUBJECT}}", subject())
                .replace("{{BADGE}}", badge)
                .replace("{{HEADING}}", heading)
                .replace("{{INTRO}}", intro.replace("{minutes}", String.valueOf(minutes)))
                .replace("{{CODE_LABEL}}", codeLabel)
                .replace("{{CODE}}", code)
                .replace("{{EXPIRY_NOTE}}", expiryNote.replace("{time}", time))
                .replace("{{SECURITY}}", security)
                .replace("{{FOOTER}}", footer)
                .replace("{{PRIVACY}}", privacy)
                .replace("{{TERMS}}", terms)
                .replace("{{FRONTEND_BASE_URL}}", base)
                .replace("{{PRIVACY_URL}}", base + "/privacy")
                .replace("{{TERMS_URL}}", base + "/terms");
    }
}
