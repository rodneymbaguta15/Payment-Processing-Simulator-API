package com.payment_processing.model;

import com.payment_processing.domain.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;


import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Getter @Setter @NoArgsConstructor
@AllArgsConstructor @Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private String merchantId;

    // Card info — never store raw PAN
    @Column(nullable = false)
    private String cardLastFour;

    @Column(nullable = false)
    private String cardNumberHash;      // SHA-256 of full PAN

    @Column(nullable = false)
    private String cardHolderName;

    @Column(nullable = false)
    private String expiryMonth;         // MM

    @Column(nullable = false)
    private String expiryYear;          // YYYY

    // Payment details
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;            // ISO 4217: USD, CAD, EUR

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    private String declineReason;       // Populated if DECLINED or FAILED

    // Refund tracking
    @Builder.Default
    @Column(precision = 19, scale = 4)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    private String originalPaymentId;   // Set on refund transactions

    // Audit timestamps
    @Builder.Default
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime authorizedAt;     //payment authorized timestamp
    private LocalDateTime capturedAt;      //payment captured timestamp
    private LocalDateTime settledAt;     //payment settled timestamp
    private LocalDateTime refundedAt;     //payment refunded timestamp
}
