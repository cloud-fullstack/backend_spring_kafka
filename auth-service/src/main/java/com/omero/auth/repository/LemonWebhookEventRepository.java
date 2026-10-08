package com.omero.auth.repository;

import com.omero.auth.entity.LemonWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LemonWebhookEventRepository extends JpaRepository<LemonWebhookEvent, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO lemon_webhook_events (event_id, event_name) VALUES (:id, :name) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("id") String id, @Param("name") String name);
}
