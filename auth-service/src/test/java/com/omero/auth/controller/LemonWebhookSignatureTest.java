package com.omero.auth.controller;

import com.omero.auth.security.LemonSignature;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LemonWebhookSignatureTest {

    @Test
    void knownVector() {
        byte[] body = "what do ya want for nothing?".getBytes(StandardCharsets.UTF_8);
        String sig = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843";
        assertTrue(LemonSignature.isValid("Jefe", body, sig));
        assertFalse(LemonSignature.isValid("Jefe", body, sig.replace('5', '6')));
        assertFalse(LemonSignature.isValid("Jefe", body, null));
        assertFalse(LemonSignature.isValid("Jefe", "altro".getBytes(StandardCharsets.UTF_8), sig));
    }
}
