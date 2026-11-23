package com.omero.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pre-contract list (Demo / Pro / one-off integrations) and sector fields at signup.
 * Amounts must match {@code subscription_plans} addons and the SPA catalog.
 */
public final class SectorOnboarding {

    public static final int DEMO_TRIAL_DAYS = 7;
    public static final int PRO_MONTHLY_EUR = 45;
    public static final int BRONZE_MONTHLY_EUR = 29;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_VALUE = 400;

    private static final Map<String, List<String>> FIELDS = Map.of(
            "hospitality", List.of("address", "unitsCount", "checkInTime", "checkOutTime", "cancelPolicy"),
            "vehicle_rental", List.of("vehicleTypes", "pickupSites", "licenseCheck", "insuranceCheck"),
            "real_estate", List.of("agentCount", "listingType", "operatingAreas", "portals"),
            "ecommerce", List.of("platform", "productRange", "carriers"),
            "medical", List.of("professionType", "professionalCount", "defaultSlotMinutes", "treatsHealthData"),
            "legal", List.of("professionType", "professionalCount", "defaultSlotMinutes"),
            "accounting", List.of("professionType", "professionalCount", "defaultSlotMinutes"),
            "restaurant", List.of());

    private SectorOnboarding() {}

    public static int proMonthlyEur(String vertical) {
        return "vehicle_rental".equals(vertical) ? BRONZE_MONTHLY_EUR : PRO_MONTHLY_EUR;
    }

    public static String prezziHash(String vertical) {
        return switch (vertical == null ? "" : vertical) {
            case "medical" -> "medico";
            case "hospitality" -> "bnb";
            case "vehicle_rental" -> "rent";
            case "real_estate" -> "immobiliare";
            case "ecommerce" -> "ecomm";
            case "legal" -> "avvocato";
            case "accounting" -> "commercialista";
            case "restaurant" -> "ristorazione";
            default -> "altro";
        };
    }

    public static Addon teaserAddon(String vertical) {
        return switch (vertical == null ? "" : vertical) {
            case "real_estate" -> new Addon("calendar_agency", 690);
            case "vehicle_rental" -> new Addon("calendar_rent", 1190);
            case "hospitality" -> new Addon("ical_integration", 490);
            case "ecommerce" -> new Addon("shop_integration", 890);
            case "medical" -> new Addon("calendar_agency", 690);
            default -> null;
        };
    }

    public static List<String> contractPaths(String vertical, Map<String, String> profile) {
        List<String> paths = new java.util.ArrayList<>();
        paths.add("/terms");
        paths.add("/dpa");
        if (needsArt9(vertical, profile)) {
            paths.add("/dpa#art9");
        }
        return List.copyOf(paths);
    }

    public static boolean needsArt9(String vertical, Map<String, String> profile) {
        if ("medical".equals(vertical)) {
            return true;
        }
        if (profile == null) {
            return false;
        }
        return "yes".equalsIgnoreCase(profile.getOrDefault("treatsHealthData", ""));
    }

    public static String withPlanIntent(String json, String planIntent) {
        String p = planIntent == null ? "" : planIntent.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("bronze", "silver", "gold", "platinum").contains(p)) {
            return json;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = json == null || json.isBlank()
                    ? new LinkedHashMap<>()
                    : JSON.readValue(json, Map.class);
            m.put("planIntent", p);
            return JSON.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            return json;
        }
    }

    public static String toJson(String vertical, Map<String, String> raw) {
        Map<String, String> clean = sanitize(vertical, raw);
        try {
            return JSON.writeValueAsString(clean);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid onboarding profile");
        }
    }

    public static Map<String, String> sanitize(String vertical, Map<String, String> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        Set<String> allowed = Set.copyOf(FIELDS.getOrDefault(vertical, List.of()));
        if (allowed.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (e.getKey() == null || !allowed.contains(e.getKey())) {
                continue;
            }
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (v.isEmpty()) {
                continue;
            }
            if (v.length() > MAX_VALUE) {
                v = v.substring(0, MAX_VALUE);
            }
            out.put(e.getKey(), v);
        }
        return out;
    }

    public static String sectorLabel(String vertical, Locale locale) {
        String lang = locale == null ? "en" : locale.getLanguage();
        boolean it = "it".equals(lang);
        return switch (vertical == null ? "" : vertical) {
            case "medical" -> it ? "Studio medico" : "Medical practice";
            case "hospitality" -> it ? "B&B / Ospitalità" : "B&B / Hospitality";
            case "vehicle_rental" -> it ? "Noleggio auto e van" : "Car & van rental";
            case "real_estate" -> it ? "Agenzia immobiliare" : "Real estate agency";
            case "ecommerce" -> "E-commerce";
            case "accounting" -> it ? "Commercialista" : "Accountant";
            case "legal" -> it ? "Avvocato / Studio legale" : "Lawyer / Law firm";
            case "restaurant" -> it ? "Ristorazione" : "Restaurant";
            default -> vertical == null ? "" : vertical;
        };
    }

    public record Addon(String code, int priceEur) {}
}
