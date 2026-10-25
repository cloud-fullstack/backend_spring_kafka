package com.omero.auth.repository;

import com.omero.auth.entity.StripeWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StripeWebhookEventRepository extends JpaRepository<StripeWebhookEvent, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO stripe_webhook_events (event_id, event_name) VALUES (:id, :name) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("id") String id, @Param("name") String name);
}
