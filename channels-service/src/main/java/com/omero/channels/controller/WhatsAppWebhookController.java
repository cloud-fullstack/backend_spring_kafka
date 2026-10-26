package com.omero.channels.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omero.channels.entity.WhatsAppAccount;
import com.omero.channels.service.LlmForwarder;
import com.omero.channels.service.WhatsAppService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@RestController
@RequestMapping("/webhooks/whatsapp")
public class WhatsAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);
    private static final String TEXT_ONLY =
            "Per ora leggo solo messaggi scritti. Scrivimi con un testo.";

    private final WhatsAppService whatsapp;
    private final LlmForwarder llm;
    private final ObjectMapper mapper;
    private final Executor webhookExecutor;
    private final String verifyToken;
    private final String appSecret;

    public WhatsAppWebhookController(
            WhatsAppService whatsapp,
            LlmForwarder llm,
            ObjectMapper mapper,
            @Qualifier("whatsappWebhookExecutor") Executor webhookExecutor,
            @Value("${meta.webhook-verify-token}") String verifyToken,
            @Value("${meta.app-secret}") String appSecret) {
        this.whatsapp = whatsapp;
        this.llm = llm;
        this.mapper = mapper;
        this.webhookExecutor = webhookExecutor;
        this.verifyToken = verifyToken;
        this.appSecret = appSecret;
    }

    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode) && verifyToken != null && verifyToken.equals(token)) {
            return ResponseEntity.ok(challenge == null ? "" : challenge);
        }
        return ResponseEntity.status(403).build();
    }

    @PostMapping
    public ResponseEntity<Void> incoming(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody byte[] raw) {
        if (!validSignature(raw, signature)) {
            return ResponseEntity.status(403).build();
        }
        try {
            CompletableFuture.runAsync(() -> handle(raw), webhookExecutor);
        } catch (RejectedExecutionException e) {
            log.warn("whatsapp webhook queue full, Meta will retry");
            throw e;
        }
        return ResponseEntity.ok().build();
    }

    private void handle(byte[] raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            for (JsonNode entry : root.path("entry")) {
                for (JsonNode change : entry.path("changes")) {
                    JsonNode value = change.path("value");
                    String phoneNumberId = value.path("metadata").path("phone_number_id").asText("");
                    WhatsAppAccount acc = whatsapp.accountByPhoneNumberId(phoneNumberId);
                    if (acc == null
                            || !"connected".equals(acc.getStatus())
                            || !whatsapp.entitled(acc.getTenantId())) {
                        continue;
                    }
                    for (JsonNode msg : value.path("messages")) {
                        String id = msg.path("id").asText("");
                        if (!whatsapp.rememberMessage(id)) {
                            continue;
                        }
                        String from = msg.path("from").asText("");
                        String type = msg.path("type").asText("text");
                        String text = msg.path("text").path("body").asText("");
                        if (!"text".equals(type) || text.isBlank()) {
                            try {
                                whatsapp.send(acc.getTenantId(), from, TEXT_ONLY);
                            } catch (Exception ignored) {
                                /* quota / disconnected */
                            }
                            continue;
                        }
                        String language = msg.path("locale").asText("");
                        llm.forward(acc.getTenantId(), from, text, id, language);
                    }
                }
            }
        } catch (Exception ignored) {
            /* Meta already got 200 */
        }
    }

    private boolean validSignature(byte[] raw, String signature) {
        if (appSecret == null || appSecret.isBlank() || signature == null || !signature.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(raw);
            byte[] given = HexFormat.of().parseHex(signature.substring(7));
            return expected.length == given.length && MessageDigest.isEqual(expected, given);
        } catch (Exception e) {
            return false;
        }
    }
}
