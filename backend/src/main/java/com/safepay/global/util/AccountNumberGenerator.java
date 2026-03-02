package com.safepay.global.util;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class AccountNumberGenerator {

    private static final String BANK_CODE = "100";
    private static final SecureRandom RANDOM = new SecureRandom();

    public String generate(String accountType) {
        String productCode = "SAVINGS".equals(accountType) ? "02" : "01";
        String serialNumber = String.format("%06d", RANDOM.nextInt(1_000_000));
        String baseNumber = BANK_CODE + productCode + serialNumber;
        int checkDigit = calculateLuhnCheckDigit(baseNumber);

        return String.format("%s-%s-%s-%d",
                BANK_CODE, productCode, serialNumber, checkDigit);
    }

    // Luhn 알고리즘으로 검증 코드 계산
    private int calculateLuhnCheckDigit(String number) {
        int sum = 0;
        boolean alternate = true;

        for (int i = number.length() - 1; i >= 0; i--) {
            int digit = Character.getNumericValue(number.charAt(i));
            if (alternate) {
                digit *= 2;
                if (digit > 9) digit -= 9;
            }
            sum += digit;
            alternate = !alternate;
        }

        return (10 - (sum % 10)) % 10;
    }
}
