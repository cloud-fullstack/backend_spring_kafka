package com.omero.auth.service;

public enum WelcomeEmailI18n {

    IT("Benvenuto su Telemaco 👋",
       "Il tuo account è stato creato con successo.<br/>Ora puoi accedere alla piattaforma e iniziare a utilizzare tutti i servizi.",
       "Accedi al tuo account →",
       "Se non hai creato questo account, puoi ignorare questa email.",
       "Per assistenza",
       "Tutti i diritti riservati."),

    EN("Welcome to Telemaco 👋",
       "Your account has been successfully created.<br/>You can now log in and start using all our services.",
       "Access your account →",
       "If you did not create this account, you can safely ignore this email.",
       "For support",
       "All rights reserved."),

    FR("Bienvenue sur Telemaco 👋",
       "Votre compte a été créé avec succès.<br/>Vous pouvez maintenant vous connecter et utiliser tous nos services.",
       "Accéder à votre compte →",
       "Si vous n'avez pas créé ce compte, vous pouvez ignorer cet email.",
       "Pour assistance",
       "Tous droits réservés."),

    DE("Willkommen bei Telemaco 👋",
       "Ihr Konto wurde erfolgreich erstellt.<br/>Sie können sich jetzt anmelden und alle unsere Dienste nutzen.",
       "Zu Ihrem Konto →",
       "Falls Sie dieses Konto nicht erstellt haben, können Sie diese E-Mail ignorieren.",
       "Für Unterstützung",
       "Alle Rechte vorbehalten."),

    ES("Bienvenido a Telemaco 👋",
       "Tu cuenta ha sido creada con éxito.<br/>Ahora puedes iniciar sesión y empezar a usar todos nuestros servicios.",
       "Acceder a tu cuenta →",
       "Si no creaste esta cuenta, puedes ignorar este correo.",
       "Para soporte",
       "Todos los derechos reservados.");

    public final String greeting;
    public final String body;
    public final String cta;
    public final String disclaimer;
    public final String supportText;
    public final String rights;

    WelcomeEmailI18n(String greeting, String body, String cta,
                     String disclaimer, String supportText, String rights) {
        this.greeting = greeting;
        this.body = body;
        this.cta = cta;
        this.disclaimer = disclaimer;
        this.supportText = supportText;
        this.rights = rights;
    }

    public String subject() {
        return switch (this) {
            case IT -> "Benvenuto su Telemaco — registrazione completata";
            case EN -> "Welcome to Telemaco — registration complete";
            case FR -> "Bienvenue sur Telemaco — inscription confirmée";
            case DE -> "Willkommen bei Telemaco — Registrierung abgeschlossen";
            case ES -> "Bienvenido a Telemaco — registro completado";
        };
    }

    /** ISO-3166 country from the register form. Null or unknown → EN. */
    public static WelcomeEmailI18n fromCountry(String country) {
        if (country == null || country.isBlank()) {
            return EN;
        }
        return switch (country.trim().toUpperCase()) {
            case "IT", "SM", "VA" -> IT;
            case "FR", "BE", "CH", "LU", "MC" -> FR;
            case "DE", "AT" -> DE;
            case "ES", "MX", "AR", "CL", "CO", "PE", "VE" -> ES;
            default -> EN;
        };
    }

    public String apply(String html, String loginUrl) {
        return html
                .replace("{{GREETING}}", greeting)
                .replace("{{BODY}}", body)
                .replace("{{CTA}}", cta)
                .replace("{{LOGIN_URL}}", loginUrl)
                .replace("{{DISCLAIMER}}", disclaimer)
                .replace("{{SUPPORT_TEXT}}", supportText)
                .replace("{{RIGHTS}}", rights);
    }
}
