package com.safepay.unit.global;

import com.safepay.global.util.AccountNumberGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AccountNumberGenerator 단위 테스트")
class AccountNumberGeneratorTest {

    private AccountNumberGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new AccountNumberGenerator();
    }

    @Test
    @DisplayName("생성된 계좌번호는 XXX-XX-XXXXXX-X 형식이다")
    void generate_hasCorrectFormat() {
        // When
        String accountNumber = generator.generate("CHECKING");

        // Then: 100-01-123456-7 형식
        assertThat(accountNumber).matches("\\d{3}-\\d{2}-\\d{6}-\\d");
    }

    @Test
    @DisplayName("CHECKING 계좌는 상품코드가 01이다")
    void generate_checking_productCode01() {
        // When
        String accountNumber = generator.generate("CHECKING");

        // Then
        assertThat(accountNumber).startsWith("100-01-");
    }

    @Test
    @DisplayName("SAVINGS 계좌는 상품코드가 02이다")
    void generate_savings_productCode02() {
        // When
        String accountNumber = generator.generate("SAVINGS");

        // Then
        assertThat(accountNumber).startsWith("100-02-");
    }

    @Test
    @DisplayName("은행코드는 항상 100이다")
    void generate_bankCodeIs100() {
        // When
        String accountNumber = generator.generate("CHECKING");

        // Then
        assertThat(accountNumber.substring(0, 3)).isEqualTo("100");
    }

    @Test
    @DisplayName("Luhn 검증코드가 정확하다")
    void generate_luhnCheckDigitIsValid() {
        // When
        String accountNumber = generator.generate("CHECKING");

        // Then: 전체 숫자에 대해 Luhn 검증
        String digits = accountNumber.replaceAll("-", "");
        assertThat(isValidLuhn(digits)).isTrue();
    }

    @Test
    @DisplayName("100개 생성 시 중복이 없다 (랜덤성 검증)")
    void generate_noDuplicatesIn100() {
        // When
        Set<String> numbers = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            numbers.add(generator.generate("CHECKING"));
        }

        // Then
        assertThat(numbers).hasSize(100);
    }

    /**
     * Luhn 알고리즘 검증
     * 마지막 자릿수(검증코드)를 포함한 전체 숫자열이 유효한지 확인
     */
    private boolean isValidLuhn(String number) {
        int sum = 0;
        boolean alternate = false;

        for (int i = number.length() - 1; i >= 0; i--) {
            int digit = Character.getNumericValue(number.charAt(i));
            if (alternate) {
                digit *= 2;
                if (digit > 9) digit -= 9;
            }
            sum += digit;
            alternate = !alternate;
        }

        return sum % 10 == 0;
    }
}