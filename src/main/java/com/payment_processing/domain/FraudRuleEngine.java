package com.payment_processing.domain;

import com.payment_processing.dto.PaymentRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Component
public class FraudRuleEngine {

    @Value("${payment.fraud.max-amount}")
    private BigDecimal maxAmount;

    @Value("${payment.fraud.blocked-cards}")
    private List<String> blockedCards;

    // Returns null if approved, or a decline reason string if rejected
    public String evaluate(PaymentRequest request) {

        // Rule 1 — Blocklisted card
        if (blockedCards.contains(request.getCardNumber())) {
            log.warn("Fraud rule triggered: blocked card ending in {}",
                    request.getCardNumber().substring(request.getCardNumber().length() - 4));
            return "Card is not accepted";
        }

        // Rule 2 — Amount exceeds maximum transaction limit
        if (request.getAmount().compareTo(maxAmount) > 0) {
            log.warn("Fraud rule triggered: amount {} exceeds limit {}", request.getAmount(), maxAmount);
            return "Transaction amount exceeds allowed limit";
        }

        // Rule 3 — Unsupported currency
        List<String> supportedCurrencies = List.of("USD", "CAD", "EUR", "GBP");
        if (!supportedCurrencies.contains(request.getCurrency())) {
            return "Currency not supported";
        }

        // Rule 4 — Suspicious round-number high-value transactions (soft fraud signal)
        if (request.getAmount().compareTo(new BigDecimal("5000.00")) > 0
                && request.getAmount().remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0) {
            log.warn("Fraud rule triggered: suspicious round-number high-value transaction");
            return "Transaction flagged for suspicious pattern";
        }

        return null; // All rules passed — approved
    }
}
