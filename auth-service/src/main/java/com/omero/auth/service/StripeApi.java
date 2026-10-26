package com.omero.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@Component
public class StripeApi {

    private static final Logger log = LoggerFactory.getLogger(StripeApi.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String secretKey;
    private final String apiVersion;

    public StripeApi(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${stripe.secret-key:}") String secretKey,
            @Value("${stripe.api-version:2026-04-22.dahlia}") String apiVersion) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.secretKey = secretKey;
        this.apiVersion = apiVersion;
    }

    public JsonNode get(String path) {
        requireSecret();
        try {
            ResponseEntity<String> res = restTemplate.exchange(
                    "https://api.stripe.com" + path,
                    HttpMethod.GET,
                    new HttpEntity<>(headers(null)),
                    String.class);
            return parse(res.getBody());
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 404) {
                return null;
            }
            log.warn("Stripe GET {} status={}", path, e.getStatusCode().value());
            throw new IllegalStateException("Stripe is not configured");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Stripe GET {} failed: {}", path, e.getMessage());
            throw new IllegalStateException("Stripe is not configured");
        }
    }

    public JsonNode post(String path, MultiValueMap<String, String> form, String idempotencyKey) {
        requireSecret();
        try {
            ResponseEntity<String> res = restTemplate.exchange(
                    "https://api.stripe.com" + path,
                    HttpMethod.POST,
                    new HttpEntity<>(form, headers(idempotencyKey)),
                    String.class);
            return parse(res.getBody());
        } catch (IllegalStateException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            log.warn("Stripe POST {} status={} body={}", path, e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new IllegalStateException("Stripe is not configured");
        } catch (Exception e) {
            log.warn("Stripe POST {} failed: {}", path, e.getMessage());
            throw new IllegalStateException("Stripe is not configured");
        }
    }

    public void delete(String path, String idempotencyKey) {
        requireSecret();
        try {
            restTemplate.exchange(
                    "https://api.stripe.com" + path,
                    HttpMethod.DELETE,
                    new HttpEntity<>(headers(idempotencyKey)),
                    String.class);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 404) {
                return;
            }
            log.warn("Stripe DELETE {} status={}", path, e.getStatusCode().value());
            throw new IllegalStateException("Stripe is not configured");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Stripe DELETE {} failed: {}", path, e.getMessage());
            throw new IllegalStateException("Stripe is not configured");
        }
    }

    public String firstPaidPaymentIntent(String invoiceId) {
        if (invoiceId == null || invoiceId.isBlank()) {
            return "";
        }
        JsonNode payload;
        try {
            payload = get("/v1/invoices/" + invoiceId + "/payments?limit=10");
        } catch (RuntimeException e) {
            return "";
        }
        if (payload == null) {
            return "";
        }
        for (JsonNode p : payload.path("data")) {
            if (!"paid".equals(text(p.path("status")))) {
                continue;
            }
            String pi = text(p.path("payment").path("payment_intent"));
            if (pi.isBlank()) {
                pi = text(p.path("payment_intent"));
            }
            if (!pi.isBlank()) {
                return pi;
            }
        }
        return "";
    }

    public HttpHeaders formHeaders(String idempotencyKey) {
        return headers(idempotencyKey);
    }

    private HttpHeaders headers(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(secretKey);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("Stripe-Version", apiVersion);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    private void requireSecret() {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("Stripe is not configured");
        }
    }

    private JsonNode parse(String body) throws Exception {
        return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
    }

    static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return "";
        }
        return n.asText("");
    }
}
