package com.omero.auth.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omero.auth.outbox.EventPublisher;
import com.omero.auth.outbox.OutboxEvent;
import com.omero.auth.outbox.TenantOutbox;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Adapter XADD: stesso contratto di EventPublisher del repo telemaco-billing-springboot. */
@Component
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisStreamEventPublisher implements EventPublisher {

    private static final long STREAM_MAXLEN = 1_000L;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisStreamEventPublisher(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public void publish(OutboxEvent event) {
        String stream = streamOf(event.getEventType());
        String json;
        try {
            json = mapper.writeValueAsString(event.getPayload() == null ? Map.of() : event.getPayload());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("outbox payload");
        }
        redis.opsForStream().add(MapRecord.create(stream, Map.of("data", json)));
        redis.opsForStream().trim(stream, STREAM_MAXLEN, true);
    }

    private static String streamOf(String eventType) {
        if (TenantOutbox.PAID.equals(eventType)) {
            return TenantEventProducer.STREAM_PAID;
        }
        if (TenantOutbox.TRIAL_WEEK.equals(eventType)) {
            return TenantEventProducer.STREAM_TRIAL7D;
        }
        if (TenantOutbox.FEATURE.equals(eventType)) {
            return TenantEventProducer.STREAM_FEATURE;
        }
        if (TenantOutbox.DEACTIVATED.equals(eventType)) {
            return TenantEventProducer.STREAM_DEACT;
        }
        throw new IllegalArgumentException("unknown outbox type");
    }
}
