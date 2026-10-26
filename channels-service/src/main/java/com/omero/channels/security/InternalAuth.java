package com.omero.channels.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class InternalAuth {

    private InternalAuth() {}

    public static void require(String expected, String provided) {
        if (expected == null || expected.isBlank() || provided == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = provided.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length || !MessageDigest.isEqual(a, b)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
