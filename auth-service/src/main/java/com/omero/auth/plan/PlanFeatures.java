package com.omero.auth.plan;

import java.util.Set;

/**
 * Mirrors subscription_plans.features in telemaco_db.
 * After a payment/suspension, pods other than the one that wrote the change
 * see the new plan within expireAfterWrite (30s).
 */
public final class PlanFeatures {

    public static final Set<String> CODES = Set.of("demo", "bronze", "silver", "gold", "platinum");
    public static final Set<String> LIVE_TENANT = Set.of("demo", "registered", "active");

    private PlanFeatures() {}

    public static boolean allowed(String planId, String feature) {
        String plan = planId == null ? "demo" : planId.toLowerCase();
        return switch (feature) {
            case "chatbot.text", "voice_replies", "chatbot.voice" -> true;
            case "analytics" -> Set.of("silver", "gold", "platinum").contains(plan);
            case "voip", "whatsapp", "android_app" -> Set.of("gold", "platinum").contains(plan);
            default -> false;
        };
    }
}
