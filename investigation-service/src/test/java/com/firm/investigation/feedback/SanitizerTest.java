package com.firm.investigation.feedback;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SanitizerTest {

    private Sanitizer sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new Sanitizer();
    }

    @Test
    void sanitize_nullInput_returnsNull() {
        assertThat(sanitizer.sanitize(null)).isNull();
    }

    @Test
    void sanitize_emptyInput_returnsEmpty() {
        assertThat(sanitizer.sanitize("")).isEqualTo("");
    }

    @Test
    void sanitize_isin_replaced() {
        String result = sanitizer.sanitize("Instrument GB00B4QFG876 not found");
        assertThat(result).isEqualTo("Instrument [ISIN] not found");
    }

    @Test
    void sanitize_longDigits_replaced() {
        String result = sanitizer.sanitize("Account 12345678901 failed");
        assertThat(result).isEqualTo("Account [NUM] failed");
    }

    @Test
    void sanitize_shortDigits_preserved() {
        String result = sanitizer.sanitize("Error at line 247");
        assertThat(result).isEqualTo("Error at line 247");  // 3 digits, not stripped
    }

    @Test
    void sanitize_tradeId_replaced() {
        String result = sanitizer.sanitize("Trade TR-ABC123XYZ failed");
        assertThat(result).isEqualTo("Trade [TRADE_ID] failed");
    }

    @Test
    void sanitize_money_replaced() {
        assertThat(sanitizer.sanitize("Amount $1234.56 transferred")).contains("[AMOUNT]");
        assertThat(sanitizer.sanitize("Amount GBP 12345 transferred")).contains("[AMOUNT]");
    }

    @Test
    void sanitize_email_replaced() {
        String result = sanitizer.sanitize("Contact user@example.com for details");
        assertThat(result).isEqualTo("Contact [EMAIL] for details");
    }

    @Test
    void sanitize_combinedSubstitutions() {
        String input = "Account 12345678 ISIN GB00B4QFG876 owner user@x.com failed";
        String result = sanitizer.sanitize(input);
        assertThat(result).contains("[NUM]").contains("[ISIN]").contains("[EMAIL]");
        assertThat(result).doesNotContain("12345678").doesNotContain("GB00B4QFG876").doesNotContain("user@x.com");
    }

    @Test
    void sanitize_plainErrorText_unchanged() {
        String input = "NullPointerException at CollateralCalculator.java";
        assertThat(sanitizer.sanitize(input)).isEqualTo(input);
    }
}
