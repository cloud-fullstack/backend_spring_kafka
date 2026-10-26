package com.omero.auth.security;

import com.omero.auth.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Service
public class JwtTokenProvider {

    private final SecretKey key;
    private final long expirationMs;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms:86400000}") long expirationMs) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("jwt.secret is required");
        }
        // Stessi byte di PyJWT: la stringa UTF-8, senza decode base64.
        // JWT_SECRET (Spring) e JWT_SECRET_KEY (backend-llm) devono essere la stessa stringa.
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("jwt.secret: almeno 32 byte UTF-8");
        }
        // HmacSHA256 fisso: Keys.hmacShaKeyFor sceglierebbe HS384/HS512 da 48/64 byte.
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
        this.expirationMs = expirationMs;
    }

    public String generateToken(User user) {
        Date now = new Date();
        String uid = user.getId().toString();
        return Jwts.builder()
                .subject(uid)
                .claim("uid", uid)
                .claim("tenant_id", user.getTenantId() != null ? user.getTenantId().toString() : null)
                .claim("tid", user.getTenantId() != null ? user.getTenantId().toString() : null)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public boolean validateToken(String token) {
        return parseUserId(token).isPresent();
    }

    public Optional<UUID> parseUserId(String token) {
        try {
            return Optional.of(UUID.fromString(parse(token).get("uid", String.class)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
