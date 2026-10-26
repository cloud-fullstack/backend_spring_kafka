package com.omero.channels.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetaErrorsTest {

    @Test
    void pinMismatch() {
        assertEquals(
                "PIN non corretto. Usa il PIN della verifica in due passaggi del numero.",
                MetaErrors.message(133005));
        ResponseStatusException ex = MetaErrors.wrap(new RuntimeException("{\"error\":{\"code\":133005}}"));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
        assertEquals("PIN non corretto. Usa il PIN della verifica in due passaggi del numero.", ex.getReason());
    }

    @Test
    void unregisteredNumber() {
        assertEquals("Il numero non risulta registrato. Riprova il collegamento.", MetaErrors.message(133010));
        ResponseStatusException ex = MetaErrors.wrap(
                new RuntimeException("{\"error\":{\"code\":133010,\"message\":\"Invalid code\"}}"));
        assertEquals("Il numero non risulta registrato. Riprova il collegamento.", ex.getReason());
    }
}
