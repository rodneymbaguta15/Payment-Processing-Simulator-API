package com.payment_processing.domain;
import com.payment_processing.exception.InvalidCardException;
import org.springframework.stereotype.Component;
import java.time.YearMonth;

@Component
public class CardValidator {

    public void validate(String cardNumber, String expiryMonth, String expiryYear) {
        validateLuhn(cardNumber);
        validateExpiry(expiryMonth, expiryYear);
    }

    // -------------------------------------------------------------------
    // Luhn Algorithm
    // Doubles every second digit from the right, subtracts 9 if > 9,
    // sums all digits — valid if total % 10 == 0
    // -------------------------------------------------------------------
    private void validateLuhn(String cardNumber) {
        int sum = 0;
        boolean doubleDigit = false;

        for (int i = cardNumber.length() - 1; i >= 0; i--) {
            int digit = Character.getNumericValue(cardNumber.charAt(i));

            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) digit -= 9;
            }

            sum += digit;
            doubleDigit = !doubleDigit;
        }

        if (sum % 10 != 0) {
            throw new InvalidCardException("Card number is invalid (failed Luhn check)");
        }
    }

    private void validateExpiry(String expiryMonth, String expiryYear) {      // Parse card expiry date and compare to current month/year
        YearMonth expiry = YearMonth.of(
                Integer.parseInt(expiryYear),
                Integer.parseInt(expiryMonth)
        );

        if (expiry.isBefore(YearMonth.now())) {
            throw new InvalidCardException("Card has expired");
        }
    }
}
