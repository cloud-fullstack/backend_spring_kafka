package com.omero.auth.redis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;

/**
 * Redis Streams verso chatbot-services (niente Kafka: Redis è già nello stack LLM).
 * Si attiva con omero.redis.enabled=true
 * (OMERO_REDIS_ENABLED o REDIS_STREAMS_ENABLED) e host del Redis LLM.
 *
 * Spring (pagamenti) e Python (chat, iCal, feature) sono processi separati.
 * Non c'è PaymentController: StripeWebhookService scrive outbox_events nella
 * stessa TX dello stato tenant; OutboxRelay fa XADD. Se Redis è giù la riga resta.
 *
 * Stream:
 *   telemacos:tenant:paid          piano pagato
 *   telemacos:tenant:trial.week    demo da 7 giorni (non :trial)
 *   telemacos:tenant:feature       toggle (ical_integration, vehicle_api, jolly)
 *   telemacos:tenant:deactivated   tenant sospeso
 *
 * Consumer Python: group telemacos-python, XREADGROUP in redis_consumer.py.
 */
@Configuration
@ConditionalOnProperty(name = "omero.redis.enabled", havingValue = "true")
public class RedisStreamsConfig {

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(
            @Value("${omero.redis.host:localhost}") String host,
            @Value("${omero.redis.port:6379}") int port,
            @Value("${omero.redis.password:}") String password) {
        RedisStandaloneConfiguration conf = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isBlank()) {
            conf.setPassword(RedisPassword.of(password));
        }
        return new LettuceConnectionFactory(conf);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory redisConnectionFactory) {
        return new StringRedisTemplate(redisConnectionFactory);
    }
}
