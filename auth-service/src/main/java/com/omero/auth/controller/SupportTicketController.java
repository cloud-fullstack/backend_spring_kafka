package com.omero.auth.controller;

import com.omero.auth.entity.User;
import com.omero.auth.service.BillingAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
public class SupportTicketController {

    private static final Logger log = LoggerFactory.getLogger(SupportTicketController.class);

    private final BillingAccountService accounts;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final String mailFrom;
    private final String supportEmail;

    public SupportTicketController(
            BillingAccountService accounts,
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${app.mail-from:}") String mailFrom,
            @Value("${app.support-email:support@telemaco.app}") String supportEmail) {
        this.accounts = accounts;
        this.mailSender = mailSender;
        this.mailFrom = mailFrom == null ? "" : mailFrom.trim();
        this.supportEmail = supportEmail == null ? "" : supportEmail.trim();
    }

    @PostMapping("/api/support/ticket")
    public ResponseEntity<Map<String, Object>> ticket(@RequestBody Map<String, Object> body) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UUID uid)) {
            throw new SecurityException("Invalid credentials");
        }
        User user = accounts.requireUser(uid);
        String mode = str(body.get("mode"));
        String error = str(body.get("error"));
        String phone = str(body.get("phoneNumber"));
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender != null && !mailFrom.isBlank() && !supportEmail.isBlank()) {
            try {
                SimpleMailMessage msg = new SimpleMailMessage();
                msg.setFrom(mailFrom);
                msg.setTo(supportEmail);
                msg.setReplyTo(user.getEmail());
                msg.setSubject("WhatsApp onboarding · " + user.getTenantId());
                msg.setText(
                        "tenant=" + user.getTenantId()
                                + "\nuser=" + user.getEmail()
                                + "\nmode=" + mode
                                + "\nphone=" + phone
                                + "\nerror=" + error);
                sender.send(msg);
            } catch (Exception e) {
                log.warn("support ticket mail failed");
            }
        } else {
            log.info("support ticket tenant={} mode={}", user.getTenantId(), mode);
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }
}
