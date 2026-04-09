package com.payment_processing.service;

import com.payment_processing.domain.CardValidator;
import com.payment_processing.domain.PaymentStatus;
import com.payment_processing.dto.PagedPaymentResponse;
import com.payment_processing.dto.PaymentRequest;
import com.payment_processing.dto.PaymentResponse;
import com.payment_processing.dto.RefundRequest;
import com.payment_processing.exception.ResourceNotFoundException;
import com.payment_processing.model.Merchant;
import com.payment_processing.model.Payment;
import com.payment_processing.repository.MerchantRepository;
import com.payment_processing.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private MerchantRepository merchantRepository;
    @Mock private AuthorizationService authorizationService;
    @Mock private CardValidator cardValidator;

    @InjectMocks
    private PaymentService paymentService;

    // ------------------------------------------------------------------
    // TEST FIXTURES
    // ------------------------------------------------------------------
    private Merchant merchant;
    private PaymentRequest validRequest;
    private Payment capturedPayment;

    @BeforeEach
    void setUp() {
        merchant = Merchant.builder()
                .id(UUID.randomUUID().toString())
                .name("Acme Store")
                .apiKey("test_key_acme_001")
                .settlementBalance(BigDecimal.ZERO)
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();

        validRequest = PaymentRequest.builder()
                .cardNumber("4111111111111111")
                .cardHolderName("Rodney Mbaguta")
                .expiryMonth("12")
                .expiryYear("2027")
                .cvv("123")
                .amount(new BigDecimal("250.00"))
                .currency("USD")
                .merchantId(merchant.getId())
                .build();

        capturedPayment = Payment.builder()
                .id(UUID.randomUUID().toString())
                .idempotencyKey(UUID.randomUUID().toString())
                .merchantId(merchant.getId())
                .cardLastFour("1111")
                .cardNumberHash("dummyhash")
                .cardHolderName("Rodney Mbaguta")
                .expiryMonth("12")
                .expiryYear("2027")
                .amount(new BigDecimal("250.00"))
                .currency("USD")
                .status(PaymentStatus.CAPTURED)
                .refundedAmount(BigDecimal.ZERO)
                .createdAt(LocalDateTime.now())
                .authorizedAt(LocalDateTime.now())
                .capturedAt(LocalDateTime.now())
                .build();
    }

    // ------------------------------------------------------------------
    // INITIATE PAYMENT
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Initiate Payment")
    class InitiatePaymentTests {

        @Test
        @DisplayName("Should return CAPTURED status for a valid authorized payment")
        void shouldReturnCapturedForValidPayment() {
            when(paymentRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(merchantRepository.findById(merchant.getId())).thenReturn(Optional.of(merchant));
            when(authorizationService.sha256(any())).thenReturn("dummyhash");
            when(authorizationService.authorize(any())).thenReturn(null); // null = approved
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PaymentResponse response = paymentService.initiatePayment(validRequest, "idem-key-001");

            assertThat(response.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(response.getDeclineReason()).isNull();
            assertThat(response.getAmount()).isEqualByComparingTo("250.00");
            verify(authorizationService).captureFromAccount(eq("4111111111111111"), eq(new BigDecimal("250.00")));
        }

        @Test
        @DisplayName("Should return DECLINED status when fraud engine rejects the payment")
        void shouldReturnDeclinedWhenFraudRuleTriggered() {
            when(paymentRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(merchantRepository.findById(merchant.getId())).thenReturn(Optional.of(merchant));
            when(authorizationService.sha256(any())).thenReturn("dummyhash");
            when(authorizationService.authorize(any())).thenReturn("Card is not accepted");
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PaymentResponse response = paymentService.initiatePayment(validRequest, "idem-key-002");

            assertThat(response.getStatus()).isEqualTo(PaymentStatus.DECLINED);
            assertThat(response.getDeclineReason()).isEqualTo("Card is not accepted");
            verify(authorizationService, never()).captureFromAccount(any(), any());
        }

        @Test
        @DisplayName("Should return DECLINED status when funds are insufficient")
        void shouldReturnDeclinedForInsufficientFunds() {
            when(paymentRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(merchantRepository.findById(merchant.getId())).thenReturn(Optional.of(merchant));
            when(authorizationService.sha256(any())).thenReturn("dummyhash");
            when(authorizationService.authorize(any())).thenReturn("Insufficient funds");
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PaymentResponse response = paymentService.initiatePayment(validRequest, "idem-key-003");

            assertThat(response.getStatus()).isEqualTo(PaymentStatus.DECLINED);
            assertThat(response.getDeclineReason()).isEqualTo("Insufficient funds");
        }

        @Test
        @DisplayName("Should return existing payment for a duplicate idempotency key")
        void shouldReturnExistingPaymentForDuplicateIdempotencyKey() {
            when(paymentRepository.findByIdempotencyKey("idem-key-dup"))
                    .thenReturn(Optional.of(capturedPayment));

            PaymentResponse response = paymentService.initiatePayment(validRequest, "idem-key-dup");

            assertThat(response.getPaymentId()).isEqualTo(capturedPayment.getId());
            assertThat(response.getStatus()).isEqualTo(PaymentStatus.CAPTURED);

            // Merchant lookup and authorization should never run
            verify(merchantRepository, never()).findById(any());
            verify(authorizationService, never()).authorize(any());
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException for unknown merchant")
        void shouldThrowForUnknownMerchant() {
            when(paymentRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(merchantRepository.findById(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.initiatePayment(validRequest, "idem-key-004"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Merchant not found");
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException for inactive merchant")
        void shouldThrowForInactiveMerchant() {
            merchant.setActive(false);
            when(paymentRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(merchantRepository.findById(merchant.getId())).thenReturn(Optional.of(merchant));

            assertThatThrownBy(() -> paymentService.initiatePayment(validRequest, "idem-key-005"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("inactive");
        }
    }

    // ------------------------------------------------------------------
    // GET PAYMENT
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Get Payment")
    class GetPaymentTests {

        @Test
        @DisplayName("Should return payment response for a valid payment ID")
        void shouldReturnPaymentForValidId() {
            when(paymentRepository.findById(capturedPayment.getId()))
                    .thenReturn(Optional.of(capturedPayment));

            PaymentResponse response = paymentService.getPayment(capturedPayment.getId());

            assertThat(response.getPaymentId()).isEqualTo(capturedPayment.getId());
            assertThat(response.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(response.getCardLastFour()).isEqualTo("1111");
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException for unknown payment ID")
        void shouldThrowForUnknownPaymentId() {
            when(paymentRepository.findById(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.getPayment("unknown-id"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Payment not found");
        }
    }

    // ------------------------------------------------------------------
    // REFUND
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Refund Payment")
    class RefundTests {

        @Test
        @DisplayName("Should apply a partial refund and set status to PARTIALLY_REFUNDED")
        void shouldApplyPartialRefund() {
            when(paymentRepository.findById(capturedPayment.getId()))
                    .thenReturn(Optional.of(capturedPayment));
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            RefundRequest refundRequest = new RefundRequest(new BigDecimal("100.00"), "Partial return");
            PaymentResponse response = paymentService.refund(capturedPayment.getId(), refundRequest);

            assertThat(response.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
            assertThat(response.getRefundedAmount()).isEqualByComparingTo("100.00");
            verify(authorizationService).refundToAccount(eq("dummyhash"), eq(new BigDecimal("100.00")));
        }

        @Test
        @DisplayName("Should apply a full refund and set status to REFUNDED")
        void shouldApplyFullRefund() {
            when(paymentRepository.findById(capturedPayment.getId()))
                    .thenReturn(Optional.of(capturedPayment));
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            RefundRequest refundRequest = new RefundRequest(new BigDecimal("250.00"), "Full refund");
            PaymentResponse response = paymentService.refund(capturedPayment.getId(), refundRequest);

            assertThat(response.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(response.getRefundedAmount()).isEqualByComparingTo("250.00");
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when refund exceeds original amount")
        void shouldThrowWhenRefundExceedsOriginal() {
            when(paymentRepository.findById(capturedPayment.getId()))
                    .thenReturn(Optional.of(capturedPayment));

            RefundRequest refundRequest = new RefundRequest(new BigDecimal("999.00"), "Overrefund");

            assertThatThrownBy(() -> paymentService.refund(capturedPayment.getId(), refundRequest))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds refundable balance");
        }

        @Test
        @DisplayName("Should throw IllegalStateException when refunding a DECLINED payment")
        void shouldThrowWhenRefundingDeclinedPayment() {
            capturedPayment.setStatus(PaymentStatus.DECLINED);
            when(paymentRepository.findById(capturedPayment.getId()))
                    .thenReturn(Optional.of(capturedPayment));

            RefundRequest refundRequest = new RefundRequest(new BigDecimal("50.00"), "Invalid");

            assertThatThrownBy(() -> paymentService.refund(capturedPayment.getId(), refundRequest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot refund a payment in status");
        }

        @Test
        @DisplayName("Should throw IllegalStateException when refunding a PENDING payment")
        void shouldThrowWhenRefundingPendingPayment() {
            capturedPayment.setStatus(PaymentStatus.PENDING);
            when(paymentRepository.findById(capturedPayment.getId()))
                    .thenReturn(Optional.of(capturedPayment));

            RefundRequest refundRequest = new RefundRequest(new BigDecimal("50.00"), "Invalid");

            assertThatThrownBy(() -> paymentService.refund(capturedPayment.getId(), refundRequest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot refund a payment in status");
        }
    }

    // ------------------------------------------------------------------
    // PAGINATED QUERIES
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Paginated Payment Queries")
    class PaginationTests {

        @Test
        @DisplayName("Should return paginated payments for a valid merchant")
        void shouldReturnPagedPaymentsForMerchant() {
            when(merchantRepository.findById(merchant.getId())).thenReturn(Optional.of(merchant));
            when(paymentRepository.findByMerchantId(eq(merchant.getId()), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(capturedPayment)));

            PagedPaymentResponse response = paymentService.getPaymentsByMerchant(
                    merchant.getId(), 0, 10, "createdAt", "desc");

            assertThat(response.getPayments()).hasSize(1);
            assertThat(response.getTotalElements()).isEqualTo(1);
            assertThat(response.isFirst()).isTrue();
            assertThat(response.isLast()).isTrue();
        }

        @Test
        @DisplayName("Should return paginated payments filtered by CAPTURED status")
        void shouldReturnPagedPaymentsByStatus() {
            when(merchantRepository.findById(merchant.getId())).thenReturn(Optional.of(merchant));
            when(paymentRepository.findByMerchantIdAndStatus(
                    eq(merchant.getId()), eq(PaymentStatus.CAPTURED), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(capturedPayment)));

            PagedPaymentResponse response = paymentService.getPaymentsByMerchantAndStatus(
                    merchant.getId(), PaymentStatus.CAPTURED, 0, 10, "createdAt", "desc");

            assertThat(response.getPayments()).hasSize(1);
            assertThat(response.getPayments().get(0).getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        }

        @Test
        @DisplayName("Should return empty page when no payments match the status filter")
        void shouldReturnEmptyPageWhenNoMatchingStatus() {
            when(merchantRepository.findById(merchant.getId())).thenReturn(Optional.of(merchant));
            when(paymentRepository.findByMerchantIdAndStatus(
                    eq(merchant.getId()), eq(PaymentStatus.DECLINED), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            PagedPaymentResponse response = paymentService.getPaymentsByMerchantAndStatus(
                    merchant.getId(), PaymentStatus.DECLINED, 0, 10, "createdAt", "desc");

            assertThat(response.getPayments()).isEmpty();
            assertThat(response.getTotalElements()).isZero();
        }
    }
}