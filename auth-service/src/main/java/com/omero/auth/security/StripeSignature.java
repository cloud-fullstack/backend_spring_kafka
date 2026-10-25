package com.omero.auth.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class StripeSignature {

    private static final long TOLERANCE_SEC = 300;

    private StripeSignature() {}

    public static boolean isValid(String secret, byte[] body, String header, long nowEpochSec) {
        if (secret == null || secret.isBlank() || body == null || header == null || header.isBlank()) {
            return false;
        }
        String ts = null;
        List<String> v1s = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String k = kv[0].trim();
            String v = kv[1].trim();
            if ("t".equals(k)) {
                ts = v;
            } else if ("v1".equals(k) && !v.isBlank()) {
                v1s.add(v);
            }
        }
        if (ts == null || v1s.isEmpty()) {
            return false;
        }
        long t;
        try {
            t = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(nowEpochSec - t) > TOLERANCE_SEC) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signed = (ts + ".").getBytes(StandardCharsets.UTF_8);
            mac.update(signed);
            mac.update(body);
            byte[] expected = HexFormat.of().formatHex(mac.doFinal()).getBytes(StandardCharsets.UTF_8);
            for (String v1 : v1s) {
                if (MessageDigest.isEqual(expected, v1.getBytes(StandardCharsets.UTF_8))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
