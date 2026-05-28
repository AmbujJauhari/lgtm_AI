package com.firm.investigation.feedback;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Strips PII / sensitive substrings from log lines and feedback text before
 * storing in the RAG memory store.
 *
 * <p>Conservative v1 rules — extend as security review identifies more patterns:
 * <ul>
 *   <li>ISINs (e.g. {@code GB00B4QFG87})</li>
 *   <li>Account numbers (long digit runs ≥ 8)</li>
 *   <li>Trade IDs (TR-prefixed alphanumeric or similar tokens)</li>
 *   <li>Monetary amounts with currency symbols ({@code $1234.56}, {@code GBP 12345})</li>
 *   <li>Email addresses</li>
 * </ul>
 */
@Component
public class Sanitizer {

    private static final Pattern ISIN = Pattern.compile("\\b[A-Z]{2}[A-Z0-9]{9}[0-9]\\b");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\b\\d{8,}\\b");
    private static final Pattern TRADE_ID = Pattern.compile("\\b(?:TR|TRADE)[-_][A-Z0-9]{6,}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MONEY = Pattern.compile(
            "(?:\\$|£|€|USD|GBP|EUR|JPY)\\s?[+-]?\\d[\\d,]*(?:\\.\\d+)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile(
            "\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b",
            Pattern.CASE_INSENSITIVE);

    public String sanitize(String input) {
        if (input == null || input.isEmpty()) return input;
        String s = input;
        s = ISIN.matcher(s).replaceAll("[ISIN]");
        s = TRADE_ID.matcher(s).replaceAll("[TRADE_ID]");
        s = MONEY.matcher(s).replaceAll("[AMOUNT]");
        s = EMAIL.matcher(s).replaceAll("[EMAIL]");
        s = LONG_DIGITS.matcher(s).replaceAll("[NUM]");
        return s;
    }
}
