package com.omero.channels.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.server.ResponseStatusException;

final class MetaErrors {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MetaErrors() {}

    static ResponseStatusException wrap(RuntimeException e) {
        if (e instanceof ResponseStatusException rse) {
            return rse;
        }
        String raw = "";
        if (e instanceof HttpStatusCodeException http) {
            raw = http.getResponseBodyAsString();
        } else if (e.getMessage() != null) {
            raw = e.getMessage();
        }
        int code = 0;
        try {
            JsonNode err = JSON.readTree(raw == null || raw.isBlank() ? "{}" : raw).path("error");
            code = err.path("code").asInt(0);
        } catch (Exception ignored) {
            /* not Graph JSON */
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message(code));
    }

    static String message(int code) {
        return switch (code) {
            case 133005 -> "PIN non corretto. Usa il PIN della verifica in due passaggi del numero.";
            case 133008, 133009 -> "Troppi tentativi con il PIN. Riprova più tardi.";
            case 133015, 133016 -> "Meta chiede di aspettare prima di registrare di nuovo il numero. Riprova più tardi.";
            case 133010 -> "Il numero non risulta registrato. Riprova il collegamento.";
            default -> "Collegamento non riuscito, contatta l'assistenza.";
        };
    }
}
