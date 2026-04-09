package com.payment_processing.domain;

public enum PaymentStatus {
    PENDING,        // Created, not yet authorized
    AUTHORIZED,     // Passed fraud checks, funds reserved
    CAPTURED,       // Merchant confirmed, ready for settlement
    SETTLED,        // Funds transferred (T+1 batch)
    DECLINED,       // Failed authorization
    FAILED,         // System/validation error
    REFUNDED,       // Fully refunded
    PARTIALLY_REFUNDED  // Partial refund applied
}
