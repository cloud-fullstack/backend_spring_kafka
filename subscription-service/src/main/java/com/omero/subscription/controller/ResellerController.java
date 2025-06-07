package com.omero.subscription.controller;

import com.omero.subscription.entity.Subscription;
import com.omero.subscription.entity.Payment;
import com.omero.subscription.service.SubscriptionService;
import com.omero.subscription.service.PaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reseller")
public class ResellerController {

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private PaymentService paymentService;

    @GetMapping("/dashboard")
    @PreAuthorize("hasRole('ROLE_RESELLER')")
    public Map<String, Object> getResellerDashboard() {
        // Get current user ID from security context
        String userId = "TODO: Get from security context";

        // Get statistics
        long totalSubscriptions = subscriptionService.countByUserId(userId);
        long activeSubscriptions = subscriptionService.countActiveByUserId(userId);
        double totalRevenue = paymentService.calculateRevenueByUserId(userId);
        double monthlyRevenue = paymentService.calculateMonthlyRevenueByUserId(userId);

        return Map.of(
            "totalSubscriptions", totalSubscriptions,
            "activeSubscriptions", activeSubscriptions,
            "totalRevenue", totalRevenue,
            "monthlyRevenue", monthlyRevenue,
            "commissionRate", getCommissionRate(userId)
        );
    }

    @GetMapping("/subscriptions")
    @PreAuthorize("hasRole('ROLE_RESELLER')")
    public List<Subscription> getResellerSubscriptions(
            @RequestParam(required = false) LocalDateTime startDate,
            @RequestParam(required = false) LocalDateTime endDate) {
        String userId = "TODO: Get from security context";
        return subscriptionService.findByUserIdAndDateRange(userId, startDate, endDate);
    }

    @GetMapping("/payments")
    @PreAuthorize("hasRole('ROLE_RESELLER')")
    public List<Payment> getResellerPayments(
            @RequestParam(required = false) LocalDateTime startDate,
            @RequestParam(required = false) LocalDateTime endDate) {
        String userId = "TODO: Get from security context";
        return paymentService.findByUserIdAndDateRange(userId, startDate, endDate);
    }

    @GetMapping("/commission")
    @PreAuthorize("hasRole('ROLE_RESELLER')")
    public double getResellerCommission() {
        String userId = "TODO: Get from security context";
        return paymentService.calculateCommissionByUserId(userId);
    }

    @PostMapping("/update-commission-rate")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public void updateCommissionRate(
            @RequestParam String resellerId,
            @RequestParam double commissionRate) {
        subscriptionService.updateCommissionRate(resellerId, commissionRate);
    }

    private double getCommissionRate(String userId) {
        // TODO: Implement actual commission rate retrieval
        return 0.15; // Default commission rate
    }
}
