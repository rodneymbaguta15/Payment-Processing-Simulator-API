package com.payment_processing.controller;

import com.payment_processing.domain.PaymentStatus;
import com.payment_processing.dto.PagedPaymentResponse;
import com.payment_processing.dto.PaymentRequest;
import com.payment_processing.dto.PaymentResponse;
import com.payment_processing.dto.RefundRequest;
import com.payment_processing.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    // POST /api/v1/payments
    @PostMapping
    public ResponseEntity<PaymentResponse> initiatePayment(
            @Valid @RequestBody PaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        PaymentResponse response = paymentService.initiatePayment(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // GET /api/v1/payments/{id}
    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable String id) {
        return ResponseEntity.ok(paymentService.getPayment(id));
    }

    // POST /api/v1/payments/{id}/refund
    @PostMapping("/{id}/refund")
    public ResponseEntity<PaymentResponse> refund(
            @PathVariable String id,
            @Valid @RequestBody RefundRequest refundRequest) {

        return ResponseEntity.ok(paymentService.refund(id, refundRequest));
    }

    // GET /api/v1/payments?merchantId=&page=0&size=10&sortBy=createdAt&direction=desc
    @GetMapping
    public ResponseEntity<PagedPaymentResponse> getPaymentsByMerchant(
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "0")   int page,
            @RequestParam(defaultValue = "10")  int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {

        return ResponseEntity.ok(
                paymentService.getPaymentsByMerchant(merchantId, page, size, sortBy, direction));
    }

    // GET /api/v1/payments/status?merchantId=&status=SETTLED&page=0&size=10
    @GetMapping("/status")
    public ResponseEntity<PagedPaymentResponse> getPaymentsByStatus(
            @RequestParam String merchantId,
            @RequestParam PaymentStatus status,
            @RequestParam(defaultValue = "0")   int page,
            @RequestParam(defaultValue = "10")  int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {

        return ResponseEntity.ok(
                paymentService.getPaymentsByMerchantAndStatus(
                        merchantId, status, page, size, sortBy, direction));
    }
}
