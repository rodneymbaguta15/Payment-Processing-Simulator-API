package com.payment_processing.repository;

import com.payment_processing.domain.PaymentStatus;
import com.payment_processing.model.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);    // To ensure idempotent processing of payments
    List<Payment> findByMerchantId(String merchantId);        // To retrieve all payments for a specific merchant
    List<Payment> findByStatus(PaymentStatus status);       // To retrieve payments by their current status (e.g., PENDING, COMPLETED, FAILED)

    Page<Payment> findByMerchantId(String merchantId, Pageable pageable);      // To support pagination when retrieving payments for a merchant
    Page<Payment> findByMerchantIdAndStatus(String merchantId, PaymentStatus status, Pageable pageable);     // To support pagination when retrieving payments for a merchant filtered by status
}
