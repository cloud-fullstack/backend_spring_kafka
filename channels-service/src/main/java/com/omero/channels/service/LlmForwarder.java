package com.omero.channels.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class LlmForwarder {

    private static final Logger log = LoggerFactory.getLogger(LlmForwarder.class);

    private final RestTemplate http;
    private final String inboundUrl;
    private final String internalToken;
    private final String env;

    public LlmForwarder(
            RestTemplate http,
            @Value("${llm.inbound-url:}") String inboundUrl,
            @Value("${internal.api-token:}") String internalToken,
            @Value("${channels.env:dev}") String env) {
        this.http = http;
        this.inboundUrl = inboundUrl == null ? "" : inboundUrl.trim();
        this.internalToken = internalToken;
        this.env = env == null || env.isBlank() ? "dev" : env.trim();
    }

    public void forward(UUID tenantId, String from, String text, String messageId, String language) {
        if (inboundUrl.isBlank() || text == null || text.isBlank()) {
            return;
        }
        try {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            if (internalToken != null && !internalToken.isBlank()) {
                h.set("X-Internal-Token", internalToken);
            }
            Map<String, Object> body = new HashMap<>();
            body.put("tenantId", tenantId.toString());
            body.put("from", from);
            body.put("text", text);
            body.put("messageId", messageId == null ? "" : messageId);
            body.put("channel", "whatsapp");
            body.put("env", env);
            if (language != null && !language.isBlank()) {
                body.put("language", language.trim());
            }
            http.postForEntity(inboundUrl, new HttpEntity<>(body, h), Void.class);
        } catch (Exception e) {
            log.warn("LLM inbound failed tenant={}", tenantId);
        }
    }
}
