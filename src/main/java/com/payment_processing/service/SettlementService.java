package com.payment_processing.service;

import com.payment_processing.domain.PaymentStatus;
import com.payment_processing.model.Merchant;
import com.payment_processing.model.Payment;
import com.payment_processing.repository.MerchantRepository;
import com.payment_processing.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final PaymentRepository paymentRepository;
    private final MerchantRepository merchantRepository;

    @Scheduled(cron = "${payment.settlement.cron}")
    @Transactional
    public void settleCaptures() {
        List<Payment> captured = paymentRepository.findByStatus(PaymentStatus.CAPTURED);

        if (captured.isEmpty()) {
            log.debug("Settlement job ran — no CAPTURED payments to settle");
            return;
        }

        log.info("Settlement job started — processing {} payment(s)", captured.size());

        for (Payment payment : captured) {
            try {
                // Credit the merchant's settlement balance
                Merchant merchant = merchantRepository.findById(payment.getMerchantId())
                        .orElse(null);

                if (merchant != null) {
                    merchant.setSettlementBalance(
                            merchant.getSettlementBalance().add(payment.getAmount()));
                    merchantRepository.save(merchant);
                }

                payment.setStatus(PaymentStatus.SETTLED);
                payment.setSettledAt(LocalDateTime.now());
                paymentRepository.save(payment);

                log.info("Payment {} SETTLED: {} {}",
                        payment.getId(), payment.getAmount(), payment.getCurrency());

            } catch (Exception e) {
                log.error("Failed to settle payment {}: {}", payment.getId(), e.getMessage());
                // Continue processing remaining payments — don't let one failure block the batch
            }
        }

        log.info("Settlement job complete — {} payment(s) settled", captured.size());
    }
}