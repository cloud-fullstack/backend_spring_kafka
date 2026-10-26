package com.omero.channels.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class TokenCipher {

    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public TokenCipher(@Value("${token.enc-key}") String encKey) {
        if (encKey == null || encKey.isBlank()) {
            throw new IllegalStateException("TOKEN_ENC_KEY: openssl rand -base64 32");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("TOKEN_ENC_KEY: base64 di 32 byte");
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("TOKEN_ENC_KEY: 32 byte dopo decode base64");
        }
        this.key = decoded;
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
            buf.put(iv);
            buf.put(ct);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("encrypt failed");
        }
    }

    public String decrypt(String packed) {
        try {
            byte[] all = Base64.getDecoder().decode(packed);
            ByteBuffer buf = ByteBuffer.wrap(all);
            byte[] iv = new byte[12];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("decrypt failed");
        }
    }
}
