package com.omero.channels.controller;

import com.omero.channels.security.InternalAuth;
import com.omero.channels.service.WhatsAppService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/whatsapp")
public class InternalWhatsAppController {

    private final WhatsAppService whatsapp;
    private final String internalToken;

    public InternalWhatsAppController(
            WhatsAppService whatsapp,
            @Value("${internal.api-token}") String internalToken) {
        this.whatsapp = whatsapp;
        this.internalToken = internalToken;
    }

    @PostMapping("/send")
    public ResponseEntity<Void> send(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        InternalAuth.require(internalToken, token);
        UUID tenantId = WhatsAppService.parseUuid(String.valueOf(body.get("tenantId")));
        whatsapp.send(tenantId, String.valueOf(body.get("to")), String.valueOf(body.get("text")));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/entitlements")
    public ResponseEntity<Void> entitlements(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        InternalAuth.require(internalToken, token);
        whatsapp.applyEntitlement(
                WhatsAppService.parseUuid(String.valueOf(body.get("tenantId"))),
                Boolean.TRUE.equals(body.get("whatsapp")));
        return ResponseEntity.noContent().build();
    }
}
