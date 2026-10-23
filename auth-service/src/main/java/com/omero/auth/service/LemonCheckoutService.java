package com.omero.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omero.auth.entity.SubscriptionPlan;
import com.omero.auth.entity.Tenant;
import com.omero.auth.entity.User;
import com.omero.auth.repository.SubscriptionPlanRepository;
import com.omero.auth.repository.TenantRepository;
import com.omero.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class LemonCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(LemonCheckoutService.class);
    private static final Set<String> ALREADY_SUBSCRIBED = Set.of("active", "on_trial");

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final SubscriptionPlanRepository planRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String storeId;
    private final String frontendBase;
    private final String customerPortalUrl;
    private final boolean testMode;

    public LemonCheckoutService(
            UserRepository userRepository,
            TenantRepository tenantRepository,
            SubscriptionPlanRepository planRepository,
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${lemonsqueezy.api-key:}") String apiKey,
            @Value("${lemonsqueezy.store-id:}") String storeId,
            @Value("${app.frontend-base-url}") String frontendBase,
            @Value("${lemonsqueezy.customer-portal-url:}") String customerPortalUrl,
            @Value("${lemonsqueezy.test-mode:true}") boolean testMode) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.planRepository = planRepository;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.storeId = storeId;
        this.frontendBase = frontendBase;
        this.customerPortalUrl = customerPortalUrl;
        this.testMode = testMode;
    }

    public String checkoutUrl(UUID userId, String planCode) {
        if (apiKey == null || apiKey.isBlank() || storeId == null || storeId.isBlank()) {
            throw new IllegalStateException("Lemon Squeezy is not configured");
        }
        User user = userRepository.findById(userId).orElseThrow(() -> new SecurityException("Invalid credentials"));
        if (!user.isActive()) {
            throw new SecurityException("Invalid credentials");
        }
        if (user.getTenantId() != null) {
            Tenant tenant = tenantRepository.findById(user.getTenantId()).orElse(null);
            if (tenant != null && tenant.getStatus() != null
                    && ALREADY_SUBSCRIBED.contains(tenant.getStatus())) {
                if (customerPortalUrl != null && !customerPortalUrl.isBlank()) {
                    return customerPortalUrl;
                }
                return frontendBase.replaceAll("/$", "") + "/billing";
            }
        }
        String code = planCode == null ? "" : planCode.trim();
        SubscriptionPlan plan = planRepository.findByCodeIgnoreCase(code)
                .orElseThrow(() -> new IllegalArgumentException("Invalid request"));
        if (plan.getLemonVariantId() == null || plan.getLemonVariantId().isBlank()) {
            throw new IllegalArgumentException("Invalid request");
        }

        Map<String, Object> custom = new LinkedHashMap<>();
        custom.put("user_id", user.getId().toString());
        if (user.getTenantId() != null) {
            custom.put("tenant_id", user.getTenantId().toString());
        }
        custom.put("plan_code", plan.getCode());

        Map<String, Object> checkoutData = new LinkedHashMap<>();
        checkoutData.put("email", user.getEmail());
        checkoutData.put("custom", custom);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("checkout_data", checkoutData);
        attributes.put("checkout_options", Map.of("embed", false));
        attributes.put("test_mode", testMode);
        attributes.put("product_options", Map.of(
                "redirect_url", frontendBase.replaceAll("/$", "") + "/billing/success"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "checkouts");
        data.put("attributes", attributes);
        data.put("relationships", Map.of(
                "store", Map.of("data", Map.of("type", "stores", "id", storeId)),
                "variant", Map.of("data", Map.of("type", "variants", "id", plan.getLemonVariantId()))
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.valueOf("application/vnd.api+json"));
        headers.set("Accept", "application/vnd.api+json");
        try {
            String body = objectMapper.writeValueAsString(Map.of("data", data));
            String response = restTemplate.postForObject(
                    "https://api.lemonsqueezy.com/v1/checkouts",
                    new HttpEntity<>(body, headers),
                    String.class);
            JsonNode url = objectMapper.readTree(response == null ? "{}" : response)
                    .path("data").path("attributes").path("url");
            if (url.isMissingNode() || url.asText().isBlank()) {
                throw new IllegalStateException("Lemon Squeezy is not configured");
            }
            return url.asText();
        } catch (IllegalStateException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            log.warn("Lemon checkout failed: status={} body={}", e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new IllegalStateException("Lemon Squeezy is not configured");
        } catch (RestClientException e) {
            log.warn("Lemon checkout failed: {}", e.getMessage());
            throw new IllegalStateException("Lemon Squeezy is not configured");
        } catch (Exception e) {
            log.warn("Lemon checkout failed: {}", e.getMessage());
            throw new IllegalStateException("Lemon Squeezy is not configured");
        }
    }
}
