package com.omero.subscription.service;

import com.omero.subscription.entity.Payment;
import com.omero.subscription.repository.PaymentRepository;
import com.omero.subscription.repository.SubscriptionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class PaymentService {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Transactional
    public Payment recordPayment(
            String lemonSqueezyPaymentId,
            String subscriptionId,
            double amount,
            String currency,
            String status) {

        // Check if subscription exists
        if (!subscriptionRepository.existsById(subscriptionId)) {
            throw new RuntimeException("Subscription not found: " + subscriptionId);
        }

        // Create payment record
        Payment payment = new Payment();
        payment.setId(UUID.randomUUID().toString());
        payment.setSubscriptionId(subscriptionId);
        payment.setLemonSqueezyPaymentId(lemonSqueezyPaymentId);
        payment.setAmount(amount);
        payment.setCurrency(currency);
        payment.setStatus(status);
        payment.setCreatedAt(LocalDateTime.now());
        payment.setUpdatedAt(LocalDateTime.now());

        // Save payment
        payment = paymentRepository.save(payment);

        // Update subscription status if needed
        if (status.equals("succeeded")) {
            subscriptionRepository.updateStatusBySubscriptionId(
                    subscriptionId,
                    "active",
                    LocalDateTime.now()
            );
        }

        return payment;
    }

    public Payment getPaymentById(String paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new RuntimeException("Payment not found: " + paymentId));
    }

    public void updatePaymentStatus(String paymentId, String status) {
        paymentRepository.updateStatusById(paymentId, status, LocalDateTime.now());
    }

    public void notifyPaymentStatusChange(String paymentId, String status) {
        // Notify other services using PostgreSQL LISTEN/NOTIFY
        jdbcTemplate.execute(
                String.format("NOTIFY payment_status_change, '%s::%s'", paymentId, status)
        );
    }
}
