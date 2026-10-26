package com.omero.channels.repository;

import com.omero.channels.entity.WhatsAppWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface WhatsAppWebhookEventRepository extends JpaRepository<WhatsAppWebhookEvent, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO whatsapp_webhook_events (message_id, received_at) VALUES (:id, now()) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("id") String id);

    @Modifying
    @Query("delete from WhatsAppWebhookEvent e where e.receivedAt < :before")
    int deleteOlderThan(@Param("before") OffsetDateTime before);
}
