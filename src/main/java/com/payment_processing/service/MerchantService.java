package com.payment_processing.service;


import com.payment_processing.domain.PaymentStatus;
import com.payment_processing.dto.MerchantSummaryResponse;
import com.payment_processing.exception.ResourceNotFoundException;
import com.payment_processing.model.Merchant;
import com.payment_processing.model.Payment;
import com.payment_processing.repository.MerchantRepository;
import com.payment_processing.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantService {

    private final MerchantRepository merchantRepository;
    private final PaymentRepository paymentRepository;

    private static final Set<PaymentStatus> SUCCESSFUL_STATUSES = Set.of(
            PaymentStatus.CAPTURED,
            PaymentStatus.SETTLED,
            PaymentStatus.PARTIALLY_REFUNDED
    );

    private static final Set<PaymentStatus> REFUNDED_STATUSES = Set.of(
            PaymentStatus.REFUNDED,
            PaymentStatus.PARTIALLY_REFUNDED
    );

    @Transactional(readOnly = true)
    public MerchantSummaryResponse getMerchantSummary(String merchantId) {

        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Merchant not found: " + merchantId));

        List<Payment> allPayments = paymentRepository.findByMerchantId(merchantId);

        int total = allPayments.size();

        int successful = (int) allPayments.stream()
                .filter(p -> SUCCESSFUL_STATUSES.contains(p.getStatus()))
                .count();

        int declined = (int) allPayments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.DECLINED
                        || p.getStatus() == PaymentStatus.FAILED)
                .count();

        int refunded = (int) allPayments.stream()
                .filter(p -> REFUNDED_STATUSES.contains(p.getStatus()))
                .count();

        BigDecimal totalVolume = allPayments.stream()
                .filter(p -> SUCCESSFUL_STATUSES.contains(p.getStatus()))
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalRefunded = allPayments.stream()
                .map(Payment::getRefundedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        double successRate = total == 0 ? 0.0 :
                BigDecimal.valueOf((double) successful / total * 100)
                        .setScale(2, RoundingMode.HALF_UP)
                        .doubleValue();

        return MerchantSummaryResponse.builder()
                .merchantId(merchant.getId())
                .merchantName(merchant.getName())
                .totalTransactions(total)
                .successfulTransactions(successful)
                .declinedTransactions(declined)
                .refundedTransactions(refunded)
                .totalVolume(totalVolume)
                .settlementBalance(merchant.getSettlementBalance())
                .totalRefunded(totalRefunded)
                .successRate(successRate)
                .build();
    }
}