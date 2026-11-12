package com.omero.auth.service;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

/** Limite catalogo e-commerce per piano. Bronze 200 prodotti; Silver senza tetto. */
@Service
public class ScrapingQuotaService {

    public static final int BRONZE_PRODUCT_LIMIT = 200;

    public int productLimit(String planCode) {
        String code = planCode == null ? "demo" : planCode.trim().toLowerCase(Locale.ROOT);
        if ("silver".equals(code) || "gold".equals(code) || "platinum".equals(code)) {
            return Integer.MAX_VALUE;
        }
        return BRONZE_PRODUCT_LIMIT;
    }

    public boolean canScrape(String planCode) {
        return true;
    }

    public Map<String, Object> authorize(String planCode) {
        int limit = productLimit(planCode);
        return Map.of(
                "allowed", canScrape(planCode),
                "product_limit", limit == Integer.MAX_VALUE ? 0 : limit,
                "unlimited", limit == Integer.MAX_VALUE
        );
    }

    public boolean needsSilverUpsell(String planCode, int productsFound) {
        return productLimit(planCode) != Integer.MAX_VALUE && productsFound > BRONZE_PRODUCT_LIMIT;
    }
}
