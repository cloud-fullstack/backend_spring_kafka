package com.omero.auth.outbox;

/** Destinazione dell'outbox: Redis Streams (XADD). */
public interface EventPublisher {
    void publish(OutboxEvent event);
}
