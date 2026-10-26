package com.omero.channels.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    @Bean(name = "whatsappWebhookExecutor")
    public Executor whatsappWebhookExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(2);
        e.setMaxPoolSize(4);
        e.setQueueCapacity(200);
        e.setThreadNamePrefix("wa-wh-");
        e.initialize();
        return e;
    }
}
