package com.digitalbank.paymentservice.domain.model;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

public record PaymentInstructionRequest(String idempotencyKey, BigDecimal amount, String currency, String description) {

    public PaymentInstructionRequest {
        idempotencyKey = requireText(idempotencyKey, "Idempotency key is required");
        Objects.requireNonNull(amount, "Payment amount is required");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }
        amount = amount.stripTrailingZeros();
        if (amount.scale() > 4) {
            throw new IllegalArgumentException("Payment amount must have no more than four decimal places");
        }
        currency = requireText(currency, "Payment currency is required").toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Payment currency must be a three-letter code");
        }
        description = description == null ? "" : description.trim();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
