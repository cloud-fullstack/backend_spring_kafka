package com.omero.auth.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock OutboxRepository outbox;
    @Mock EventPublisher publisher;

    @Test
    void marksPublishedAfterXadd() {
        OutboxEvent event = new OutboxEvent("t1", TenantOutbox.PAID, java.util.Map.of("event", "tenant.paid"));
        when(outbox.lockNextBatch()).thenReturn(List.of(event));
        new OutboxRelay(outbox, publisher).relay();
        verify(publisher).publish(event);
        org.junit.jupiter.api.Assertions.assertNotNull(event.getPublishedAt());
    }

    @Test
    void leavesUnpublishedIfRedisDown() {
        OutboxEvent event = new OutboxEvent("t1", TenantOutbox.PAID, java.util.Map.of());
        when(outbox.lockNextBatch()).thenReturn(List.of(event));
        doThrow(new IllegalStateException("Redis is not configured")).when(publisher).publish(event);
        new OutboxRelay(outbox, publisher).relay();
        assertNull(event.getPublishedAt());
    }
}
