package com.omero.auth.service;

import com.omero.auth.entity.User;
import com.omero.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final String frontendBase;
    private final int ttlMinutes;

    public PasswordResetService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${app.frontend-base-url}") String frontendBase,
            @Value("${app.password-reset-ttl-minutes:30}") int ttlMinutes) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
        this.frontendBase = frontendBase;
        this.ttlMinutes = ttlMinutes;
    }

    @Transactional
    public void forgot(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            return;
        }
        userRepository.findByEmailIgnoreCase(rawEmail.trim().toLowerCase(Locale.ROOT)).ifPresent(user -> {
            byte[] bytes = new byte[32];
            RNG.nextBytes(bytes);
            String token = HexFormat.of().formatHex(bytes);
            user.setResetToken(token);
            user.setResetTokenExpires(OffsetDateTime.now().plusMinutes(ttlMinutes));
            userRepository.save(user);
            sendMail(user.getEmail(), token);
        });
    }

    @Transactional
    public void reset(String token, String password) {
        if (token == null || token.isBlank() || password == null || password.length() < 10) {
            throw new IllegalArgumentException("Invalid request");
        }
        User user = userRepository.findByResetToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid request"));
        if (user.getResetTokenExpires() == null || user.getResetTokenExpires().isBefore(OffsetDateTime.now())) {
            throw new IllegalArgumentException("Invalid request");
        }
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setResetToken(null);
        user.setResetTokenExpires(null);
        userRepository.save(user);
    }

    private void sendMail(String to, String token) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn("SMTP not configured; password reset mail not sent");
            return;
        }
        String link = frontendBase.replaceAll("/$", "") + "/reset-password?token=" + token;
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo(to);
            msg.setSubject("Password reset");
            msg.setText("Reset your password: " + link + "\nThis link expires in " + ttlMinutes + " minutes.");
            sender.send(msg);
        } catch (Exception e) {
            log.warn("Password reset mail failed");
        }
    }
}
