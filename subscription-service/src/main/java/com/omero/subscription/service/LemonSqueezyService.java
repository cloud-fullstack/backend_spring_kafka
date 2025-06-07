package com.omero.subscription.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Service
public class LemonSqueezyService {

    @Autowired
    private OkHttpClient client;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${lemon.squeezy.store.id}")
    private String storeId;

    private static final String BASE_URL = "https://api.lemonsqueezy.com/v1";

    public Map<String, Object> createSubscription(String userId, String planId) throws IOException {
        String url = String.format("%s/stores/%s/subscriptions", BASE_URL, storeId);
        Map<String, Object> body = new HashMap<>();
        body.put("data", Map.of(
            "type", "subscriptions",
            "attributes", Map.of(
                "customer_id", userId,
                "plan_id", planId
            )
        ));

        Request request = new Request.Builder()
                .url(url)
                .post(okhttp3.RequestBody.create(
                    objectMapper.writeValueAsString(body),
                    okhttp3.MediaType.parse("application/json")
                ))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
            return objectMapper.readValue(response.body().string(), HashMap.class);
        }
    }

    public Map<String, Object> getSubscription(String subscriptionId) throws IOException {
        String url = String.format("%s/stores/%s/subscriptions/%s", BASE_URL, storeId, subscriptionId);
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
            return objectMapper.readValue(response.body().string(), HashMap.class);
        }
    }

    public Map<String, Object> cancelSubscription(String subscriptionId) throws IOException {
        String url = String.format("%s/stores/%s/subscriptions/%s", BASE_URL, storeId, subscriptionId);
        Request request = new Request.Builder()
                .url(url)
                .delete()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
            return objectMapper.readValue(response.body().string(), HashMap.class);
        }
    }

    public Map<String, Object> updateSubscription(String subscriptionId, String newPlanId) throws IOException {
        String url = String.format("%s/stores/%s/subscriptions/%s", BASE_URL, storeId, subscriptionId);
        Map<String, Object> body = new HashMap<>();
        body.put("data", Map.of(
            "type", "subscriptions",
            "attributes", Map.of(
                "plan_id", newPlanId
            )
        ));

        Request request = new Request.Builder()
                .url(url)
                .patch(okhttp3.RequestBody.create(
                    objectMapper.writeValueAsString(body),
                    okhttp3.MediaType.parse("application/json")
                ))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
            return objectMapper.readValue(response.body().string(), HashMap.class);
        }
    }

    public Map<String, Object> getPlans() throws IOException {
        String url = String.format("%s/stores/%s/plans", BASE_URL, storeId);
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
            return objectMapper.readValue(response.body().string(), HashMap.class);
        }
    }
}
