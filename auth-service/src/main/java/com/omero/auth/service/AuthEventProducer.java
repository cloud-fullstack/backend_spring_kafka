package com.omero.auth.service;

import com.omero.auth.dto.AuthEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    
    @Transactional
    public void sendAuthEvent(AuthEvent event) {
        kafkaTemplate.send("user.auth.events", event);
    }

    @Transactional
    public void sendPlanUpdate(String userId, String oldPlan, String newPlan) {
        PlanUpdateEvent event = new PlanUpdateEvent();
        event.setUserId(userId);
        event.setOldPlan(oldPlan);
        event.setNewPlan(newPlan);
        kafkaTemplate.send("plan.updates", event);
    }

    @Transactional
    public void sendAuthError(String userId, String errorMessage) {
        AuthErrorEvent event = new AuthErrorEvent();
        event.setUserId(userId);
        event.setErrorMessage(errorMessage);
        kafkaTemplate.send("auth_errors", event);
    }
}
