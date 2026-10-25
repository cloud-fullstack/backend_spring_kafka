package com.omero.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omero.auth.entity.BillingAccount;
import com.omero.auth.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

@Service
public class StripeCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(StripeCheckoutService.class);
    private static final long MIN_CENTS = 500;
    private static final long MAX_CENTS = 5_000_000L;

    private final BillingAccountService accounts;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String secretKey;
    private final String apiVersion;
    private final String taxCode;
    private final String frontendBase;

    public StripeCheckoutService(
            BillingAccountService accounts,
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${stripe.secret-key:}") String secretKey,
            @Value("${stripe.api-version:2026-04-22.dahlia}") String apiVersion,
            @Value("${stripe.credits-tax-code:txcd_10105002}") String taxCode,
            @Value("${app.frontend-base-url}") String frontendBase) {
        this.accounts = accounts;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.secretKey = secretKey;
        this.apiVersion = apiVersion;
        this.taxCode = taxCode;
        this.frontendBase = frontendBase;
    }

    public String creditsCheckoutUrl(UUID userId, long amountCents) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("Stripe is not configured");
        }
        if (amountCents < MIN_CENTS || amountCents > MAX_CENTS) {
            throw new IllegalArgumentException("Invalid amount");
        }
        User user = accounts.requireUser(userId);
        BillingAccount account = accounts.ensure(user.getTenantId());
        String base = frontendBase.replaceAll("/$", "");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("mode", "payment");
        form.add("success_url", base + "/dashboard?tab=billing&credits=ok");
        form.add("cancel_url", base + "/dashboard?tab=billing");
        form.add("client_reference_id", user.getId().toString());
        if (account.getStripeCustomerId() != null && !account.getStripeCustomerId().isBlank()) {
            form.add("customer", account.getStripeCustomerId());
        } else {
            form.add("customer_email", user.getEmail());
            form.add("customer_creation", "always");
        }
        form.add("metadata[user_id]", user.getId().toString());
        form.add("metadata[tenant_id]", user.getTenantId().toString());
        form.add("metadata[kind]", "credits");
        form.add("metadata[credit_cents]", Long.toString(amountCents));
        form.add("line_items[0][quantity]", "1");
        form.add("line_items[0][price_data][currency]", "eur");
        form.add("line_items[0][price_data][unit_amount]", Long.toString(amountCents));
        form.add("line_items[0][price_data][tax_behavior]", "exclusive");
        form.add("line_items[0][price_data][product_data][name]", "Telemaco credits");
        form.add("line_items[0][price_data][product_data][tax_code]", taxCode);
        form.add("managed_payments[enabled]", "true");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(secretKey);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("Stripe-Version", apiVersion);
        try {
            String response = restTemplate.postForObject(
                    "https://api.stripe.com/v1/checkout/sessions",
                    new HttpEntity<>(form, headers),
                    String.class);
            JsonNode url = objectMapper.readTree(response == null ? "{}" : response).path("url");
            if (url.isMissingNode() || url.asText().isBlank()) {
                throw new IllegalStateException("Stripe is not configured");
            }
            return url.asText();
        } catch (IllegalStateException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            log.warn(
                    "Stripe checkout API error (keys are present): status={} body={}",
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString());
            throw new IllegalStateException("Stripe is not configured");
        } catch (Exception e) {
            log.warn("Stripe checkout failed: {}", e.getMessage());
            throw new IllegalStateException("Stripe is not configured");
        }
    }
}
