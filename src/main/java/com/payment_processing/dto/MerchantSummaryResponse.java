package com.payment_processing.dto;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantSummaryResponse {

    private String merchantId;
    private String merchantName;

    // Volume
    private int totalTransactions;
    private int successfulTransactions;  // CAPTURED + SETTLED
    private int declinedTransactions;
    private int refundedTransactions;    // REFUNDED + PARTIALLY_REFUNDED

    // Financials
    private BigDecimal totalVolume;          // Sum of all CAPTURED + SETTLED amounts
    private BigDecimal settlementBalance;    // Funds already settled to merchant
    private BigDecimal totalRefunded;        // Sum of all refunded amounts

    // Rate
    private double successRate;              // calculated from successfulTransactions / totalTransactions * 100
}
