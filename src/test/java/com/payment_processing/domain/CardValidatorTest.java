package com.payment_processing.domain;

import com.payment_processing.exception.InvalidCardException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class CardValidatorTest {

    private CardValidator cardValidator;

    @BeforeEach
    void setUp() {
        cardValidator = new CardValidator();
    }

    // ------------------------------------------------------------------
    // LUHN ALGORITHM
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Luhn Algorithm Validation")
    class LuhnTests {

        @Test
        @DisplayName("Should pass for a valid Visa test card number")
        void shouldPassForValidVisaCard() {
            assertThatNoException()
                    .isThrownBy(() -> cardValidator.validate("4111111111111111", "12", "2027"));
        }

        @Test
        @DisplayName("Should pass for a valid Mastercard test card number")
        void shouldPassForValidMastercard() {
            assertThatNoException()
                    .isThrownBy(() -> cardValidator.validate("5500005555555559", "12", "2027"));
        }

        @Test
        @DisplayName("Should pass for a valid Amex test card number")
        void shouldPassForValidAmex() {
            assertThatNoException()
                    .isThrownBy(() -> cardValidator.validate("371449635398431", "12", "2027"));
        }

        @Test
        @DisplayName("Should fail for a card number with an invalid checksum")
        void shouldFailForInvalidChecksum() {
            // Valid Visa prefix but last digit changed from 1 to 2 — breaks Luhn
            assertThatThrownBy(() -> cardValidator.validate("4111111111111112", "12", "2027"))
                    .isInstanceOf(InvalidCardException.class)
                    .hasMessageContaining("failed Luhn check");
        }


        @Test
        @DisplayName("Should fail for a sequential card number")
        void shouldFailForSequentialDigits() {
            assertThatThrownBy(() -> cardValidator.validate("1234567890123456", "12", "2027"))
                    .isInstanceOf(InvalidCardException.class)
                    .hasMessageContaining("failed Luhn check");
        }
    }

    // ------------------------------------------------------------------
    // EXPIRY VALIDATION
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Expiry Date Validation")
    class ExpiryTests {

        @Test
        @DisplayName("Should pass for a card expiring in the future")
        void shouldPassForFutureExpiry() {
            assertThatNoException()
                    .isThrownBy(() -> cardValidator.validate("4111111111111111", "12", "2099"));
        }

        @Test
        @DisplayName("Should fail for a card that expired last year")
        void shouldFailForExpiredLastYear() {
            assertThatThrownBy(() -> cardValidator.validate("4111111111111111", "01", "2020"))
                    .isInstanceOf(InvalidCardException.class)
                    .hasMessageContaining("expired");
        }

        @Test
        @DisplayName("Should fail for a card that expired last month")
        void shouldFailForExpiredLastMonth() {
            // Dynamically compute last month to keep the test evergreen
            java.time.YearMonth lastMonth = java.time.YearMonth.now().minusMonths(1);
            String month = String.format("%02d", lastMonth.getMonthValue());
            String year  = String.valueOf(lastMonth.getYear());

            assertThatThrownBy(() -> cardValidator.validate("4111111111111111", month, year))
                    .isInstanceOf(InvalidCardException.class)
                    .hasMessageContaining("expired");
        }

        @Test
        @DisplayName("Should pass for a card expiring this month")
        void shouldPassForExpiryThisMonth() {
            java.time.YearMonth thisMonth = java.time.YearMonth.now();
            String month = String.format("%02d", thisMonth.getMonthValue());
            String year  = String.valueOf(thisMonth.getYear());

            assertThatNoException()
                    .isThrownBy(() -> cardValidator.validate("4111111111111111", month, year));
        }
    }

    // ------------------------------------------------------------------
    // COMBINED VALIDATION
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Combined Validation")
    class CombinedTests {

        @Test
        @DisplayName("Should fail on Luhn before checking expiry when card number is invalid")
        void shouldFailLuhnBeforeExpiry() {
            // Invalid Luhn AND expired — Luhn runs first
            assertThatThrownBy(() -> cardValidator.validate("4111111111111112", "01", "2020"))
                    .isInstanceOf(InvalidCardException.class)
                    .hasMessageContaining("failed Luhn check");
        }

        @Test
        @DisplayName("Should fail expiry when card number is valid but card is expired")
        void shouldFailExpiryWithValidLuhn() {
            assertThatThrownBy(() -> cardValidator.validate("4111111111111111", "01", "2020"))
                    .isInstanceOf(InvalidCardException.class)
                    .hasMessageContaining("expired");
        }
    }
}