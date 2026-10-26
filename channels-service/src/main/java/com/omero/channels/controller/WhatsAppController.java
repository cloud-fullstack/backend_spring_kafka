package com.omero.channels.controller;

import com.omero.channels.security.JwtTenant;
import com.omero.channels.service.WhatsAppService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/whatsapp")
public class WhatsAppController {

    private final WhatsAppService whatsapp;

    public WhatsAppController(WhatsAppService whatsapp) {
        this.whatsapp = whatsapp;
    }

    @PostMapping("/onboarding")
    public Map<String, Object> onboarding(@RequestBody Map<String, Object> body) {
        return whatsapp.startOnboarding(tenantId(), body);
    }

    @PostMapping("/connect")
    public Map<String, Object> connect(@RequestBody Map<String, Object> body) {
        return whatsapp.connect(tenantId(), body);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return whatsapp.status(tenantId());
    }

    @DeleteMapping("/connect")
    public ResponseEntity<Void> disconnect() {
        whatsapp.disconnect(tenantId());
        return ResponseEntity.noContent().build();
    }

    private UUID tenantId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof JwtTenant t)) {
            throw new org.springframework.security.access.AccessDeniedException("Invalid credentials");
        }
        return t.tenantId();
    }
}
