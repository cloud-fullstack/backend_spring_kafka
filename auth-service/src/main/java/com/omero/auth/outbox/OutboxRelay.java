package com.omero.auth.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * OutboxRelay.relay()  ogni 2 s  @Scheduled  @Transactional
 *   ├── outbox.lockNextBatch()               SELECT FOR UPDATE SKIP LOCKED
 *   ├── publisher.publish(event)             XADD telemacos:tenant:feature
 *   └── event.markPublished()                UPDATE outbox_events
 *
 * Se Redis è giù, markPublished non gira e la riga resta unpublished.
 * redis_consumer.py XREADGROUP → _handle_feature → ical_sync_loop (30 min).
 */
@Component
@ConditionalOnBean(EventPublisher.class)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final EventPublisher publisher;

    public OutboxRelay(OutboxRepository outbox, EventPublisher publisher) {
        this.outbox = outbox;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.delay-ms:2000}")
    @Transactional
    public void relay() {
        for (OutboxEvent event : outbox.lockNextBatch()) {
            try {
                publisher.publish(event);
                event.markPublished();
            } catch (RuntimeException e) {
                log.warn("outbox relay id={} type={}: {}", event.getId(), event.getEventType(), e.getMessage());
            }
        }
    }
}
