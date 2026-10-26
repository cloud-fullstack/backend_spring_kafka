package com.omero.channels.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

@Service
public class JwtTokenParser {

    private final SecretKey key;

    public JwtTokenParser(@Value("${jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("jwt.secret: almeno 32 byte UTF-8");
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
    }

    public Optional<JwtTenant> parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            String tenant = c.get("tenant_id", String.class);
            if (tenant == null || tenant.isBlank()) {
                tenant = c.get("tid", String.class);
            }
            if (tenant == null || tenant.isBlank()) {
                return Optional.empty();
            }
            String uid = c.get("uid", String.class);
            if (uid == null) {
                uid = c.getSubject();
            }
            return Optional.of(new JwtTenant(UUID.fromString(uid), UUID.fromString(tenant)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
