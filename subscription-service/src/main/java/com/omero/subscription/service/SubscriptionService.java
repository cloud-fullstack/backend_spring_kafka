package com.omero.subscription.service;

import com.omero.subscription.entity.Subscription;
import com.omero.subscription.repository.SubscriptionRepository;
import com.omero.subscription.service.LemonSqueezyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class SubscriptionService {

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private LemonSqueezyService lemonSqueezyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Transactional
    public Subscription createSubscription(
            String userId,
            String planId,
            String lemonSqueezySubscriptionId) {

        // Create subscription record
        Subscription subscription = new Subscription();
        subscription.setId(UUID.randomUUID().toString());
        subscription.setUserId(userId);
        subscription.setPlanId(planId);
        subscription.setLemonSqueezySubscriptionId(lemonSqueezySubscriptionId);
        subscription.setStatus("active");
        subscription.setStartDate(LocalDateTime.now());
        subscription.setActive(true);
        subscription.setCreatedAt(LocalDateTime.now());
        subscription.setUpdatedAt(LocalDateTime.now());

        // Save subscription
        subscription = subscriptionRepository.save(subscription);

        // Notify other services
        jdbcTemplate.execute(
                String.format("NOTIFY subscription_created, '%s'", subscription.getId())
        );

        return subscription;
    }

    @Transactional
    public Subscription updateSubscriptionStatus(
            String subscriptionId,
            String status) {

        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found: " + subscriptionId));

        subscription.setStatus(status);
        subscription.setUpdatedAt(LocalDateTime.now());

        if (status.equals("cancelled")) {
            subscription.setActive(false);
        }

        // Save updated subscription
        subscription = subscriptionRepository.save(subscription);

        // Notify other services
        jdbcTemplate.execute(
                String.format("NOTIFY subscription_updated, '%s::%s'", subscriptionId, status)
        );

        return subscription;
    }

    public Subscription getSubscriptionById(String subscriptionId) {
        return subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found: " + subscriptionId));
    }

    public void cancelSubscription(String subscriptionId) {
        try {
            // Cancel in Lemon Squeezy
            lemonSqueezyService.cancelSubscription(subscriptionId);
            
            // Update local status
            updateSubscriptionStatus(subscriptionId, "cancelled");
        } catch (Exception e) {
            throw new RuntimeException("Failed to cancel subscription", e);
        }
    }

    public void updateSubscriptionPlan(
            String subscriptionId,
            String newPlanId) {
        try {
            // Update in Lemon Squeezy
            lemonSqueezyService.updateSubscription(subscriptionId, newPlanId);
            
            // Update local subscription
            Subscription subscription = getSubscriptionById(subscriptionId);
            subscription.setPlanId(newPlanId);
            subscription.setUpdatedAt(LocalDateTime.now());
            subscriptionRepository.save(subscription);

            // Notify other services
            jdbcTemplate.execute(
                    String.format("NOTIFY subscription_plan_updated, '%s::%s'", subscriptionId, newPlanId)
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to update subscription plan", e);
        }
    }
}
