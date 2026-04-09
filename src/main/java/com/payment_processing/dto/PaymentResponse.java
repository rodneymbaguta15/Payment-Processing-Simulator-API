package com.payment_processing.dto;

import com.payment_processing.domain.PaymentStatus;
import lombok.Builder;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentResponse {
    private String paymentId;
    private PaymentStatus status;
    private BigDecimal amount;
    private String currency;
    private String cardLastFour;
    private String merchantId;
    private String declineReason;        // null if payment is approved
    private BigDecimal refundedAmount;
    private LocalDateTime createdAt;
    private LocalDateTime authorizedAt;
    private LocalDateTime settledAt;
}
