package com.omero.subscription.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;

@Configuration
public class LemonSqueezyConfig {

    @Value("${lemon.squeezy.api.key}")
    private String apiKey;

    @Value("${lemon.squeezy.store.id}")
    private String storeId;

    @Bean
    public OkHttpClient lemonSqueezyClient() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);

        return new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    var request = chain.request().newBuilder()
                            .addHeader("Authorization", "Bearer " + apiKey)
                            .addHeader("Accept", "application/json")
                            .build();
                    return chain.proceed(request);
                })
                .addInterceptor(logging)
                .build();
    }

    @Bean
    public String lemonSqueezyStoreId() {
        return storeId;
    }
}
