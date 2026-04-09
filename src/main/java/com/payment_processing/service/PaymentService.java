package com.payment_processing.service;

import com.payment_processing.domain.CardValidator;
import com.payment_processing.domain.PaymentStatus;
import com.payment_processing.dto.PagedPaymentResponse;
import com.payment_processing.dto.PaymentRequest;
import com.payment_processing.dto.PaymentResponse;
import com.payment_processing.dto.RefundRequest;
import com.payment_processing.exception.InvalidCardException;
import com.payment_processing.exception.ResourceNotFoundException;
import com.payment_processing.model.Merchant;
import com.payment_processing.model.Payment;
import com.payment_processing.repository.MerchantRepository;
import com.payment_processing.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final MerchantRepository merchantRepository;
    private final AuthorizationService authorizationService;
    private final CardValidator cardValidator;

    // ------------------------------------------------------------------
    // INITIATE PAYMENT
    // ------------------------------------------------------------------
    @Transactional
    public PaymentResponse initiatePayment(PaymentRequest request, String idempotencyKey) {

        // 1 — Idempotency check: return existing result if key already seen
        if (idempotencyKey != null) {
            var existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Duplicate request detected for idempotency key: {}", idempotencyKey);
                return toResponse(existing.get());
            }
        }

        // 2 — Validate merchant exists and is active
        Merchant merchant = merchantRepository.findById(request.getMerchantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Merchant not found: " + request.getMerchantId()));

        if (!merchant.isActive()) {
            throw new ResourceNotFoundException("Merchant account is inactive");
        }
        // 3 — Card validation BEFORE saving anything
        //     Throws InvalidCardException → caught by GlobalExceptionHandler → 422
        //     No payment record is created for invalid cards
        cardValidator.validate(
                request.getCardNumber(),
                request.getExpiryMonth(),
                request.getExpiryYear()
        );

        // 4 — Build and save initial payment record
        String cardHash = authorizationService.sha256(request.getCardNumber());
        String lastFour = request.getCardNumber().substring(request.getCardNumber().length() - 4);

        Payment payment = Payment.builder()
                .idempotencyKey(idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString())
                .merchantId(request.getMerchantId())
                .cardLastFour(lastFour)
                .cardNumberHash(cardHash)
                .cardHolderName(request.getCardHolderName())
                .expiryMonth(request.getExpiryMonth())
                .expiryYear(request.getExpiryYear())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .status(PaymentStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();

        paymentRepository.save(payment);

        // 4 — Fraud check + authorization
        try {
            String declineReason = authorizationService.authorize(request);

            if (declineReason != null) {
                // Declined by fraud engine or insufficient funds
                payment.setStatus(PaymentStatus.DECLINED);
                payment.setDeclineReason(declineReason);
                log.info("Payment {} DECLINED: {}", payment.getId(), declineReason);
            } else {
                // Authorized — move to CAPTURED immediately in this simulator
                // (in real systems, AUTHORIZED and CAPTURED can be separate steps)
                authorizationService.captureFromAccount(request.getCardNumber(), request.getAmount());
                payment.setStatus(PaymentStatus.CAPTURED);
                payment.setAuthorizedAt(LocalDateTime.now());
                payment.setCapturedAt(LocalDateTime.now());
                log.info("Payment {} CAPTURED: {} {}", payment.getId(), request.getAmount(), request.getCurrency());
            }

        } catch (InvalidCardException e) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setDeclineReason(e.getMessage());
            log.warn("Payment {} FAILED (card error): {}", payment.getId(), e.getMessage());
        } catch (Exception e) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setDeclineReason("Internal processing error");
            log.error("Payment {} FAILED (system error): {}", payment.getId(), e.getMessage());
        }

        paymentRepository.save(payment);
        return toResponse(payment);
    }

    // ------------------------------------------------------------------
    // GET PAYMENT BY ID
    // ------------------------------------------------------------------
    @Transactional(readOnly = true)
    public PaymentResponse getPayment(String paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));
        return toResponse(payment);
    }

    // ------------------------------------------------------------------
    // REFUND
    // ------------------------------------------------------------------
    @Transactional
    public PaymentResponse refund(String paymentId, RefundRequest refundRequest) {

        Payment original = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));

        if (original.getStatus() != PaymentStatus.CAPTURED
                && original.getStatus() != PaymentStatus.SETTLED
                && original.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new IllegalStateException(
                    "Cannot refund a payment in status: " + original.getStatus());
        }

        BigDecimal alreadyRefunded = original.getRefundedAmount();
        BigDecimal refundable = original.getAmount().subtract(alreadyRefunded)
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal requestedAmount = refundRequest.getAmount()
                .setScale(4, RoundingMode.HALF_UP);

        if (requestedAmount.compareTo(refundable) > 0) {
            throw new IllegalArgumentException(
                    "Refund amount " + refundRequest.getAmount()
                            + " exceeds refundable balance of " + refundable);
        }

        authorizationService.refundToAccount(original.getCardNumberHash(), refundRequest.getAmount());

        BigDecimal newRefundedTotal = alreadyRefunded.add(refundRequest.getAmount())
                .setScale(4, RoundingMode.HALF_UP);
        original.setRefundedAmount(newRefundedTotal);
        original.setRefundedAt(LocalDateTime.now());

        // Scale-safe comparison — subtract and check remainder
        BigDecimal remaining = original.getAmount()
                .subtract(newRefundedTotal)
                .setScale(4, RoundingMode.HALF_UP);

        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            original.setStatus(PaymentStatus.REFUNDED);
        } else {
            original.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
        }

        paymentRepository.save(original);
        log.info("Refund of {} applied to payment {}, new status: {}",
                refundRequest.getAmount(), paymentId, original.getStatus());

        return toResponse(original);
    }

    // ------------------------------------------------------------------
// GET ALL PAYMENTS FOR A MERCHANT (paginated)
// ------------------------------------------------------------------
    @Transactional(readOnly = true)
    public PagedPaymentResponse getPaymentsByMerchant(
            String merchantId, int page, int size, String sortBy, String direction) {

        // Validate merchant exists
        merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Merchant not found: " + merchantId));

        Sort sort = direction.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);
        Page<Payment> result = paymentRepository.findByMerchantId(merchantId, pageable);

        return toPagedResponse(result);
    }

    // ------------------------------------------------------------------
// GET PAYMENTS FOR A MERCHANT FILTERED BY STATUS (paginated)
// ------------------------------------------------------------------
    @Transactional(readOnly = true)
    public PagedPaymentResponse getPaymentsByMerchantAndStatus(
            String merchantId, PaymentStatus status, int page, int size,
            String sortBy, String direction) {

        // Validate merchant exists
        merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Merchant not found: " + merchantId));

        Sort sort = direction.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);
        Page<Payment> result = paymentRepository.findByMerchantIdAndStatus(merchantId, status, pageable);

        return toPagedResponse(result);
    }

    // ------------------------------------------------------------------
    // MAPPER
    // ------------------------------------------------------------------
    private PaymentResponse toResponse(Payment payment) {
        return PaymentResponse.builder()
                .paymentId(payment.getId())
                .status(payment.getStatus())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .cardLastFour(payment.getCardLastFour())
                .merchantId(payment.getMerchantId())
                .declineReason(payment.getDeclineReason())
                .refundedAmount(payment.getRefundedAmount())
                .createdAt(payment.getCreatedAt())
                .authorizedAt(payment.getAuthorizedAt())
                .settledAt(payment.getSettledAt())
                .build();
    }

    // ------------------------------------------------------------------
   // SHARED PAGE MAPPER
  // ------------------------------------------------------------------
    private PagedPaymentResponse toPagedResponse(Page<Payment> page) {
        return PagedPaymentResponse.builder()
                .payments(page.getContent().stream().map(this::toResponse).toList())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .first(page.isFirst())
                .last(page.isLast())
                .build();
    }
}