package com.omero.channels.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

@Component
public class MetaGraphClient {

    private final RestTemplate http;
    private final String appId;
    private final String appSecret;
    private final String version;

    public MetaGraphClient(
            RestTemplate http,
            @Value("${meta.app-id}") String appId,
            @Value("${meta.app-secret}") String appSecret,
            @Value("${meta.graph-version}") String version) {
        this.http = http;
        this.appId = appId;
        this.appSecret = appSecret;
        this.version = version;
    }

    public String appId() {
        return appId;
    }

    public String exchangeCode(String code) {
        requireConfigured();
        URI uri = UriComponentsBuilder
                .fromUriString("https://graph.facebook.com/" + version + "/oauth/access_token")
                .queryParam("client_id", appId)
                .queryParam("client_secret", appSecret)
                .queryParam("code", code)
                .encode()
                .build()
                .toUri();
        Map<?, ?> body = http.getForObject(uri, Map.class);
        Object token = body == null ? null : body.get("access_token");
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Meta token exchange failed");
        }
        return token.toString();
    }

    public boolean phoneBelongsToWaba(String wabaId, String phoneNumberId, String token) {
        Map<?, ?> body = get("/" + wabaId + "/phone_numbers?fields=id", token);
        Object data = body == null ? null : body.get("data");
        if (!(data instanceof java.util.List<?> list)) {
            return false;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m && phoneNumberId.equals(String.valueOf(m.get("id")))) {
                return true;
            }
        }
        return false;
    }

    public Map<?, ?> get(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<Map> r = http.exchange(
                "https://graph.facebook.com/" + version + path,
                HttpMethod.GET,
                new HttpEntity<>(h),
                Map.class);
        return r.getBody();
    }

    public void subscribeApp(String wabaId, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        http.exchange(
                "https://graph.facebook.com/" + version + "/" + wabaId + "/subscribed_apps",
                HttpMethod.POST,
                new HttpEntity<>(h),
                Map.class);
    }

    public void registerPhone(String phoneNumberId, String token, String pin) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        http.exchange(
                "https://graph.facebook.com/" + version + "/" + phoneNumberId + "/register",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("messaging_product", "whatsapp", "pin", pin), h),
                Map.class);
    }

    public void sendText(String phoneNumberId, String token, String to, String text) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "text",
                "text", Map.of("body", text));
        http.exchange(
                "https://graph.facebook.com/" + version + "/" + phoneNumberId + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(body, h),
                Map.class);
    }

    private void requireConfigured() {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Meta is not configured");
        }
    }
}
