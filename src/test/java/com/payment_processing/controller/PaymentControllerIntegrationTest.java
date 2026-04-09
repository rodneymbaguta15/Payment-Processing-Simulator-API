package com.payment_processing.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment_processing.domain.PaymentStatus;
import com.payment_processing.dto.PaymentRequest;
import com.payment_processing.dto.RefundRequest;
import com.payment_processing.model.Merchant;
import com.payment_processing.model.SimulatedAccount;
import com.payment_processing.repository.MerchantRepository;
import com.payment_processing.repository.SimulatedAccountRepository;
import com.payment_processing.service.AuthorizationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentControllerIntegrationTest {

    // ------------------------------------------------------------------
    // TESTCONTAINERS — shared PostgreSQL container for all tests
    // ------------------------------------------------------------------
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("payment_simulator_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // ------------------------------------------------------------------
    // SPRING DEPENDENCIES
    // ------------------------------------------------------------------
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MerchantRepository merchantRepository;
    @Autowired SimulatedAccountRepository accountRepository;
    @Autowired AuthorizationService authorizationService;

    // ------------------------------------------------------------------
    // SHARED STATE across ordered tests
    // ------------------------------------------------------------------
    static String merchantId;
    static String capturedPaymentId;  // used for GET tests
    static String refundPaymentId;    // dedicated payment for refund sequence

    // ------------------------------------------------------------------
    // SEED DATA — runs once before all tests
    // ------------------------------------------------------------------
    @BeforeAll
    static void seedDatabase(
            @Autowired MerchantRepository merchantRepository,
            @Autowired SimulatedAccountRepository accountRepository,
            @Autowired AuthorizationService authorizationService) {

        Merchant merchant = merchantRepository.save(
                Merchant.builder()
                        .name("Test Merchant")
                        .apiKey("test_key_integration_001")
                        .settlementBalance(BigDecimal.ZERO)
                        .active(true)
                        .build()
        );
        merchantId = merchant.getId();

        // Sufficient funds — primary test card
        accountRepository.save(SimulatedAccount.builder()
                .cardNumberHash(authorizationService.sha256("4111111111111111"))
                .lastFour("1111")
                .balance(new BigDecimal("9999.00"))
                .reservedBalance(BigDecimal.ZERO)
                .build());

        // Low balance — for insufficient funds test
        accountRepository.save(SimulatedAccount.builder()
                .cardNumberHash(authorizationService.sha256("4242424242424242"))
                .lastFour("4242")
                .balance(new BigDecimal("10.00"))
                .reservedBalance(BigDecimal.ZERO)
                .build());

        // Blocked card — for fraud rule test
        accountRepository.save(SimulatedAccount.builder()
                .cardNumberHash(authorizationService.sha256("4000000000000002"))
                .lastFour("0002")
                .balance(new BigDecimal("9999.00"))
                .reservedBalance(BigDecimal.ZERO)
                .build());

        // Dedicated card used ONLY for the refund test sequence — fully isolated
        accountRepository.save(SimulatedAccount.builder()
                .cardNumberHash(authorizationService.sha256("5500005555555559"))
                .lastFour("5559")
                .balance(new BigDecimal("9999.00"))
                .reservedBalance(BigDecimal.ZERO)
                .build());
    }

    // ------------------------------------------------------------------
    // HELPERS
    // ------------------------------------------------------------------
    private PaymentRequest buildRequest(String cardNumber, BigDecimal amount) {
        return PaymentRequest.builder()
                .cardNumber(cardNumber)
                .cardHolderName("Rodney Mbaguta")
                .expiryMonth("12")
                .expiryYear("2027")
                .cvv("123")
                .amount(amount)
                .currency("USD")
                .merchantId(merchantId)
                .build();
    }

    // Dedicated helper for the refund sequence — uses a separate Mastercard
    // that is never touched by any other test
    private PaymentRequest buildRefundSequenceRequest(BigDecimal amount) {
        return PaymentRequest.builder()
                .cardNumber("5500005555555559")
                .cardHolderName("Rodney Mbaguta")
                .expiryMonth("12")
                .expiryYear("2027")
                .cvv("123")
                .amount(amount)
                .currency("USD")
                .merchantId(merchantId)
                .build();
    }

    // ==================================================================
    // 1. INITIATE PAYMENT — SUCCESS
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("POST /payments — should return 201 CAPTURED for a valid payment")
    void shouldReturn201ForValidPayment() throws Exception {
        PaymentRequest request = buildRequest("4111111111111111", new BigDecimal("250.00"));

        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "integ-key-001")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.amount").value(250.00))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.cardLastFour").value("1111"))
                .andExpect(jsonPath("$.declineReason").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        capturedPaymentId = objectMapper.readTree(body).get("paymentId").asText();
    }

    // ==================================================================
    // 2. IDEMPOTENCY
    // ==================================================================

    @Test
    @Order(2)
    @DisplayName("POST /payments — should return same response for duplicate idempotency key")
    void shouldReturnSameResponseForDuplicateIdempotencyKey() throws Exception {
        PaymentRequest request = buildRequest("4111111111111111", new BigDecimal("250.00"));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "integ-key-001") // same key as Order(1)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value(capturedPaymentId))
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    // ==================================================================
    // 3. GET PAYMENT BY ID
    // ==================================================================

    @Test
    @Order(3)
    @DisplayName("GET /payments/{id} — should return payment details")
    void shouldReturnPaymentById() throws Exception {
        mockMvc.perform(get("/api/v1/payments/{id}", capturedPaymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(capturedPaymentId))
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.cardLastFour").value("1111"));
    }

    @Test
    @Order(4)
    @DisplayName("GET /payments/{id} — should return 404 for unknown payment ID")
    void shouldReturn404ForUnknownPaymentId() throws Exception {
        mockMvc.perform(get("/api/v1/payments/{id}", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("Payment not found")));
    }

    // ==================================================================
    // 4. DECLINED PAYMENTS
    // ==================================================================

    @Test
    @Order(5)
    @DisplayName("POST /payments — should return DECLINED for a blocked card")
    void shouldReturnDeclinedForBlockedCard() throws Exception {
        PaymentRequest request = buildRequest("4000000000000002", new BigDecimal("100.00"));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "integ-key-blocked")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.declineReason").value("Card is not accepted"));
    }

    @Test
    @Order(6)
    @DisplayName("POST /payments — should return DECLINED for insufficient funds")
    void shouldReturnDeclinedForInsufficientFunds() throws Exception {
        PaymentRequest request = buildRequest("4242424242424242", new BigDecimal("500.00"));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "integ-key-insufficient")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.declineReason").value("Insufficient funds"));
    }

    @Test
    @Order(7)
    @DisplayName("POST /payments — should return DECLINED for amount exceeding fraud limit")
    void shouldReturnDeclinedForAmountExceedingFraudLimit() throws Exception {
        PaymentRequest request = buildRequest("4111111111111111", new BigDecimal("15000.00"));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "integ-key-fraud")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.declineReason")
                        .value("Transaction amount exceeds allowed limit"));
    }

    // ==================================================================
    // 5. VALIDATION ERRORS
    // ==================================================================

    @Test
    @Order(8)
    @DisplayName("POST /payments — should return 400 for missing required fields")
    void shouldReturn400ForMissingFields() throws Exception {
        PaymentRequest request = PaymentRequest.builder()
                .cardNumber("4111111111111111")
                .build();

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.fields").exists());
    }

    @Test
    @Order(9)
    @DisplayName("POST /payments — should return 422 for expired card")
    void shouldReturn422ForExpiredCard() throws Exception {
        PaymentRequest request = PaymentRequest.builder()
                .cardNumber("4111111111111111")
                .cardHolderName("Rodney Mbaguta")
                .expiryMonth("01")
                .expiryYear("2020")
                .cvv("123")
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .merchantId(merchantId)
                .build();

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "integ-key-expired")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(containsString("expired")));
    }

    @Test
    @Order(10)
    @DisplayName("POST /payments — should return 422 for card number that fails Luhn check")
    void shouldReturn422ForInvalidCardNumber() throws Exception {
        PaymentRequest request = buildRequest("1234567890123456", new BigDecimal("100.00"));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "integ-key-invalid-card")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(containsString("failed Luhn check")));
    }

    // ==================================================================
    // 6. REFUND SEQUENCE
    // Tests 11–15 are strictly ordered and stateful:
    //   11 → creates the payment        (sets refundPaymentId)
    //   12 → partial refund $100        → PARTIALLY_REFUNDED
    //   13 → remaining refund $150      → REFUNDED  (100 + 150 = 250)
    //   14 → refund on REFUNDED payment → 409
    //   15 → over-refund on fresh pay   → 400
    // Uses dedicated Mastercard 5500005555555559 — never touched elsewhere.
    // ==================================================================

    @Test
    @Order(11)
    @DisplayName("POST /payments — create dedicated payment for refund test sequence")
    void shouldCreateDedicatedPaymentForRefundTests() throws Exception {
        PaymentRequest request = buildRefundSequenceRequest(new BigDecimal("250.00"));

        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "integ-key-refund-base")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.amount").value(250.00))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        refundPaymentId = objectMapper.readTree(body).get("paymentId").asText();
        Assertions.assertNotNull(refundPaymentId, "refundPaymentId must be set before refund tests run");
    }

    @Test
    @Order(12)
    @DisplayName("POST /payments/{id}/refund — should apply partial refund of $100")
    void shouldApplyPartialRefund() throws Exception {
        RefundRequest refundRequest = RefundRequest.builder()
                .amount(new BigDecimal("100.00"))
                .reason("Partial return")
                .build();

        mockMvc.perform(post("/api/v1/payments/{id}/refund", refundPaymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refundRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(100.00));
    }

    @Test
    @Order(13)
    @DisplayName("POST /payments/{id}/refund — should refund remaining $150 and mark REFUNDED")
    void shouldApplyFullRemainingRefund() throws Exception {
        // $100 was refunded in Order(12), remaining is $150 → total $250 → REFUNDED
        RefundRequest refundRequest = RefundRequest.builder()
                .amount(new BigDecimal("150.00"))
                .reason("Remaining refund")
                .build();

        mockMvc.perform(post("/api/v1/payments/{id}/refund", refundPaymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refundRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(250.00));
    }

    @Test
    @Order(14)
    @DisplayName("POST /payments/{id}/refund — should return 409 when payment is already REFUNDED")
    void shouldReturn409ForAlreadyRefundedPayment() throws Exception {
        RefundRequest refundRequest = RefundRequest.builder()
                .amount(new BigDecimal("50.00"))
                .reason("Should fail")
                .build();

        mockMvc.perform(post("/api/v1/payments/{id}/refund", refundPaymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refundRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("Cannot refund")));
    }

    @Test
    @Order(15)
    @DisplayName("POST /payments/{id}/refund — should return 400 when refund amount exceeds original")
    void shouldReturn400WhenRefundExceedsOriginal() throws Exception {
        // Create a fresh $100 payment on the dedicated refund card
        PaymentRequest paymentRequest = buildRefundSequenceRequest(new BigDecimal("100.00"));

        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "integ-key-overrefund")
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andReturn();

        String newPaymentId = objectMapper.readTree(
                result.getResponse().getContentAsString()).get("paymentId").asText();

        // Attempt to refund $999 against a $100 payment
        RefundRequest refundRequest = RefundRequest.builder()
                .amount(new BigDecimal("999.00"))
                .reason("Over refund")
                .build();

        mockMvc.perform(post("/api/v1/payments/{id}/refund", newPaymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refundRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("exceeds refundable balance")));
    }

    // ==================================================================
    // 7. PAGINATED QUERIES
    // ==================================================================

    @Test
    @Order(16)
    @DisplayName("GET /payments — should return paginated payments for a merchant")
    void shouldReturnPaginatedPaymentsForMerchant() throws Exception {
        mockMvc.perform(get("/api/v1/payments")
                        .param("merchantId", merchantId)
                        .param("page", "0")
                        .param("size", "10")
                        .param("sortBy", "createdAt")
                        .param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments").isArray())
                .andExpect(jsonPath("$.payments", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(greaterThan(0)))
                .andExpect(jsonPath("$.first").value(true));
    }

    @Test
    @Order(17)
    @DisplayName("GET /payments/status — should return only DECLINED payments")
    void shouldReturnOnlyDeclinedPayments() throws Exception {
        mockMvc.perform(get("/api/v1/payments/status")
                        .param("merchantId", merchantId)
                        .param("status", "DECLINED")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments").isArray())
                .andExpect(jsonPath("$.payments", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.payments[*].status", everyItem(is("DECLINED"))));
    }

    @Test
    @Order(18)
    @DisplayName("GET /payments/status — should return empty page for PENDING status")
    void shouldReturnEmptyPageForStatusWithNoResults() throws Exception {
        // Payments are immediately transitioned to CAPTURED/DECLINED —
        // no payment ever remains in PENDING status after processing
        mockMvc.perform(get("/api/v1/payments/status")
                        .param("merchantId", merchantId)
                        .param("status", "PENDING")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments").isArray())
                .andExpect(jsonPath("$.payments", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @Order(19)
    @DisplayName("GET /payments/status — should return 400 for invalid status value")
    void shouldReturn400ForInvalidStatusValue() throws Exception {
        mockMvc.perform(get("/api/v1/payments/status")
                        .param("merchantId", merchantId)
                        .param("status", "INVALID_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("INVALID_STATUS")));
    }

    // ==================================================================
    // 8. MERCHANT SUMMARY
    // ==================================================================

    @Test
    @Order(20)
    @DisplayName("GET /merchants/{id}/summary — should return correct merchant dashboard stats")
    void shouldReturnMerchantSummary() throws Exception {
        mockMvc.perform(get("/api/v1/merchants/{id}/summary", merchantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value(merchantId))
                .andExpect(jsonPath("$.merchantName").value("Test Merchant"))
                .andExpect(jsonPath("$.totalTransactions").value(greaterThan(0)))
                .andExpect(jsonPath("$.successfulTransactions").value(greaterThan(0)))
                .andExpect(jsonPath("$.declinedTransactions").value(greaterThan(0)))
                .andExpect(jsonPath("$.totalVolume").isNumber())
                .andExpect(jsonPath("$.settlementBalance").isNumber())
                .andExpect(jsonPath("$.successRate").isNumber());
    }

    @Test
    @Order(21)
    @DisplayName("GET /merchants/{id}/summary — should return 404 for unknown merchant ID")
    void shouldReturn404ForUnknownMerchant() throws Exception {
        mockMvc.perform(get("/api/v1/merchants/{id}/summary", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("Merchant not found")));
    }
}