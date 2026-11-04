package com.omero.auth.controller;

import com.omero.auth.dto.PartnerApplicationRequest;
import com.omero.auth.dto.PartnerContractCreateRequest;
import com.omero.auth.dto.PartnerSignRequest;
import com.omero.auth.service.PartnerProgramService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/reseller")
public class ResellerController {

    private final PartnerProgramService partners;

    public ResellerController(PartnerProgramService partners) {
        this.partners = partners;
    }

    @PostMapping("/applications")
    public ResponseEntity<Map<String, Object>> apply(
            @RequestBody @Valid PartnerApplicationRequest request,
            HttpServletRequest http) {
        PartnerProgramService.ApplicationResult result = partners.apply(request, http);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("token", result.token());
        body.put("referralCode", result.referralCode());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        return ResponseEntity.ok(partners.me(currentUserId()));
    }

    @PostMapping("/contracts")
    public ResponseEntity<Map<String, Object>> createContract(
            @RequestBody @Valid PartnerContractCreateRequest request) {
        return ResponseEntity.ok(partners.ensureContract(currentUserId(), request.getType()));
    }

    @GetMapping("/contracts/{id}")
    public ResponseEntity<Map<String, Object>> contract(@PathVariable UUID id) {
        return ResponseEntity.ok(partners.contract(currentUserId(), id));
    }

    @PostMapping("/contracts/{id}/accept")
    public ResponseEntity<Map<String, Object>> accept(
            @PathVariable UUID id,
            @RequestBody @Valid PartnerSignRequest request,
            HttpServletRequest http) {
        return ResponseEntity.ok(partners.accept(currentUserId(), id, request, http));
    }

    private UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UUID uid)) {
            throw new SecurityException("Invalid credentials");
        }
        return uid;
    }
}
