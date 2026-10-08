package com.omero.auth.controller;

import com.omero.auth.entity.Reseller;
import com.omero.auth.entity.User;
import com.omero.auth.repository.ResellerRepository;
import com.omero.auth.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/reseller")
public class ResellerController {

    private final UserRepository userRepository;
    private final ResellerRepository resellerRepository;

    public ResellerController(UserRepository userRepository, ResellerRepository resellerRepository) {
        this.userRepository = userRepository;
        this.resellerRepository = resellerRepository;
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UUID uid)) {
            throw new SecurityException("Invalid credentials");
        }
        User user = userRepository.findById(uid).orElseThrow(() -> new SecurityException("Invalid credentials"));
        if (!user.isReseller()) {
            throw new SecurityException("Invalid credentials");
        }
        Reseller reseller = resellerRepository.findByUserId(uid).orElse(null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", reseller == null ? "pending" : reseller.getStatus());
        body.put("referralCode", reseller == null ? null : reseller.getReferralCode());
        body.put("companyName", reseller == null ? null : reseller.getCompanyName());
        return ResponseEntity.ok(body);
    }
}
