package com.omero.auth.service;

import java.util.Locale;
import java.util.Map;

/**
 * Form / API codes → two different strings, stored on {@code tenants}:
 *
 * <ul>
 *   <li>{@link #normalizeVertical} → {@code business_verticals.code}
 *       ({@code medical}, {@code vehicle_rental}, {@code hospitality}, …)</li>
 *   <li>{@link #normalizeBusinessType} → {@code business_types.code} / LLM handler
 *       ({@code doctor}, {@code vehicle}, {@code bnb}, {@code lawyer}, {@code pizzeria}, …).
 *       This is <em>not</em> {@code booking_prompt.sector_key()}: Python still aliases
 *       {@code doctor→medical}, {@code pizzeria→restaurant}, {@code lab→laboratory}.</li>
 * </ul>
 *
 * Unknown vertical → {@code "other"}. Unknown business type → {@code null} (no handler).
 */
public final class TenantVerticals {

    private static final Map<String, String> VERTICALS = Map.ofEntries(
            Map.entry("doctor", "medical"),
            Map.entry("lab-analysis", "medical"),
            Map.entry("lab", "medical"),
            Map.entry("medical", "medical"),
            Map.entry("vehicle-rental", "vehicle_rental"),
            Map.entry("vehicle_rental", "vehicle_rental"),
            Map.entry("vehicle", "vehicle_rental"),
            Map.entry("bnb-booking", "hospitality"),
            Map.entry("bnb", "hospitality"),
            Map.entry("hotel", "hospitality"),
            Map.entry("hospitality", "hospitality"),
            Map.entry("accountant", "legal"),
            Map.entry("lawyer", "legal"),
            Map.entry("legal", "legal"),
            Map.entry("accounting", "accounting"),
            Map.entry("pizza-restaurant", "restaurant"),
            Map.entry("pizzeria", "restaurant"),
            Map.entry("restaurant", "restaurant"),
            Map.entry("e-commerce", "ecommerce"),
            Map.entry("ecommerce", "ecommerce"));

    private static final Map<String, String> BUSINESS_TYPES = Map.ofEntries(
            Map.entry("doctor", "doctor"),
            Map.entry("medical", "doctor"),
            Map.entry("lab-analysis", "lab"),
            Map.entry("lab", "lab"),
            Map.entry("vehicle-rental", "vehicle"),
            Map.entry("vehicle_rental", "vehicle"),
            Map.entry("vehicle", "vehicle"),
            Map.entry("bnb-booking", "bnb"),
            Map.entry("hotel", "bnb"),
            Map.entry("hospitality", "bnb"),
            Map.entry("bnb", "bnb"),
            Map.entry("accountant", "lawyer"),
            Map.entry("accounting", "lawyer"),
            Map.entry("lawyer", "lawyer"),
            Map.entry("legal", "lawyer"),
            Map.entry("pizza-restaurant", "pizzeria"),
            Map.entry("restaurant", "pizzeria"),
            Map.entry("pizzeria", "pizzeria"),
            Map.entry("e-commerce", "ecommerce"),
            Map.entry("ecommerce", "ecommerce"));

    private TenantVerticals() {}

    public static String normalizeVertical(String raw) {
        if (raw == null) {
            return "other";
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return "other";
        }
        return VERTICALS.getOrDefault(key, "other");
    }

    /** Codes in {@code business_types}; {@code null} when there is no matching handler. */
    public static String normalizeBusinessType(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return null;
        }
        return BUSINESS_TYPES.get(key);
    }
}
