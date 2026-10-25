package com.omero.auth.security;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StripeSignatureTest {

    @Test
    void acceptsFreshV1AndRejectsStaleOrWrong() throws Exception {
        byte[] body = "{\"id\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8);
        long t = 1_700_000_000L;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("whsec_test".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
        String v1 = HexFormat.of().formatHex(mac.doFinal(body));
        String header = "t=" + t + ",v1=" + v1;
        assertTrue(StripeSignature.isValid("whsec_test", body, header, t));
        assertTrue(StripeSignature.isValid("whsec_test", body, "t=" + t + ",v1=dead,v1=" + v1, t));
        assertFalse(StripeSignature.isValid("whsec_test", body, header, t + 301));
        assertFalse(StripeSignature.isValid("", body, header, t));
        assertFalse(StripeSignature.isValid("whsec_test", body, "t=" + t + ",v1=dead", t));
    }
}
