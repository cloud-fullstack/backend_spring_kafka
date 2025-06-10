package com.omero.subscription.controller;

import com.omero.subscription.dto.PaymentRequest;
import com.omero.subscription.dto.PaymentResponse;
import com.omero.subscription.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class PaymentController {
    
    private final PaymentService paymentService;
    
    @PostMapping("/process")
    public ResponseEntity<PaymentResponse> processPayment(@RequestBody PaymentRequest request) {
        return ResponseEntity.ok(paymentService.processPayment(request));
    }
    
    @GetMapping("/status/{paymentId}")
    public ResponseEntity<PaymentResponse> getPaymentStatus(@PathVariable String paymentId) {
        return ResponseEntity.ok(paymentService.getPaymentStatus(paymentId));
    }
    
    @PostMapping("/refund/{paymentId}")
    public ResponseEntity<PaymentResponse> refundPayment(@PathVariable String paymentId) {
        return ResponseEntity.ok(paymentService.refundPayment(paymentId));
    }
    
    @PostMapping("/cancel/{paymentId}")
    public ResponseEntity<Void> cancelPayment(@PathVariable String paymentId) {
        paymentService.cancelPayment(paymentId);
        return ResponseEntity.ok().build();
    }
}
