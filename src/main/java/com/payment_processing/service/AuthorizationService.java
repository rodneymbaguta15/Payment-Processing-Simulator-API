package com.payment_processing.service;

import com.payment_processing.domain.CardValidator;
import com.payment_processing.domain.FraudRuleEngine;
import com.payment_processing.dto.PaymentRequest;
import com.payment_processing.exception.InvalidCardException;
import com.payment_processing.model.SimulatedAccount;
import com.payment_processing.repository.SimulatedAccountRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorizationService {

    private final CardValidator cardValidator;
    private final FraudRuleEngine fraudRuleEngine;
    private final SimulatedAccountRepository accountRepository;

    // Returns null if authorized, or a decline reason string
    @Transactional(propagation = Propagation.REQUIRES_NEW)     // Run in a separate transaction to allow independent rollback on failure
    public String authorize(PaymentRequest request) {

        // 1 — Card validation (throws InvalidCardException if invalid)
        cardValidator.validate(request.getCardNumber(), request.getExpiryMonth(), request.getExpiryYear());

        // 2 — Fraud rules
        String fraudDeclineReason = fraudRuleEngine.evaluate(request);
        if (fraudDeclineReason != null) {
            return fraudDeclineReason;
        }

        // 3 — Account lookup + balance check
        String cardHash = sha256(request.getCardNumber());
        SimulatedAccount account = accountRepository.findByCardNumberHash(cardHash)
                .orElseThrow(() -> new InvalidCardException("Card not recognized"));

        BigDecimal available = account.getBalance().subtract(account.getReservedBalance());
        if (available.compareTo(request.getAmount()) < 0) {
            log.warn("Insufficient funds for card ending in {}: available={}, requested={}",
                    request.getCardNumber().substring(request.getCardNumber().length() - 4),
                    available, request.getAmount());
            return "Insufficient funds";
        }

        // 4 — Reserve funds (authorization hold)
        account.setReservedBalance(account.getReservedBalance().add(request.getAmount()));
        accountRepository.save(account);
        log.info("Funds reserved: {} {} for card ending in {}",
                request.getAmount(), request.getCurrency(),
                request.getCardNumber().substring(request.getCardNumber().length() - 4));

        return null; // Authorized
    }

    // Called on capture — moves reserved funds to actual deduction
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void captureFromAccount(String cardNumber, BigDecimal amount) {
        String cardHash = sha256(cardNumber);
        SimulatedAccount account = accountRepository.findByCardNumberHash(cardHash)
                .orElseThrow(() -> new InvalidCardException("Card account not found during capture"));

        account.setReservedBalance(account.getReservedBalance().subtract(amount));
        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(account);
        log.info("Funds captured: {} from card account", amount);
    }

    // Called on refund — restores balance
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refundToAccount(String cardNumberHash, BigDecimal amount) {
        SimulatedAccount account = accountRepository.findByCardNumberHash(cardNumberHash)
                .orElseThrow(() -> new InvalidCardException("Card account not found during refund"));

        account.setBalance(account.getBalance().add(amount));
        accountRepository.save(account);
        log.info("Funds refunded: {} to card account", amount);
    }

    public String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash card number", e);
        }
    }
}