package com.omero.auth.service;

import com.omero.auth.config.TurnstileProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
public class TurnstileService {

    private static final Logger log = LoggerFactory.getLogger(TurnstileService.class);
    private static final String SITEVERIFY_URL =
            "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final TurnstileProperties props;
    private final RestTemplate restTemplate;

    public TurnstileService(
            TurnstileProperties props,
            @Qualifier("turnstileRestTemplate") RestTemplate restTemplate) {
        this.props = props;
        this.restTemplate = restTemplate;
    }

    /**
     * Verifica il token Turnstile.
     * Se Turnstile non è abilitato o il secret è vuoto, passa sempre (safe default).
     */
    public boolean verify(String token) {
        if (!props.isEnabled() || props.getSecret() == null || props.getSecret().isBlank()) {
            return true;
        }
        if (token == null || token.isBlank()) {
            log.warn("Turnstile: token assente con verifica abilitata");
            return false;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("secret", props.getSecret());
            body.add("response", token);

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.postForObject(
                    SITEVERIFY_URL,
                    new HttpEntity<>(body, headers),
                    Map.class);
            boolean success = Boolean.TRUE.equals(resp != null ? resp.get("success") : Boolean.FALSE);
            if (!success) {
                log.warn("Turnstile: verifica fallita — error-codes: {}",
                        resp != null ? resp.get("error-codes") : "null");
            }
            return success;
        } catch (Exception ex) {
            log.error("Turnstile: errore chiamata siteverify", ex);
            return false;
        }
    }
}
