package com.omero.common.transport.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omero.common.transport.MessageTransport;
import com.omero.common.transport.TransportMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.Serializable;

@Service
public class RestTransport<T extends Serializable> implements MessageTransport<T> {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private TransportMode currentMode = TransportMode.AUTO;

    public RestTransport(RestTemplate restTemplate, ObjectMapper objectMapper, 
                         @Value("${rest.base.url}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    @Override
    public void send(String endpoint, T payload) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            String jsonPayload = objectMapper.writeValueAsString(payload);
            HttpEntity<String> request = new HttpEntity<>(jsonPayload, headers);
            
            restTemplate.postForEntity(baseUrl + endpoint, request, Void.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send REST request", e);
        }
    }

    @Override
    public void setMode(TransportMode mode) {
        this.currentMode = mode;
    }

    @Override
    public TransportMode getCurrentMode() {
        return currentMode;
    }
}
