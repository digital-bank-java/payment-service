package com.digitalbank.paymentservice.application.port.in;

import com.digitalbank.paymentservice.domain.model.PaymentInstructionRequest;
import java.math.BigDecimal;

public record CreatePaymentInstructionCommand(
        String idempotencyKey, String correlationId, BigDecimal amount, String currency, String description) {

    public CreatePaymentInstructionCommand {
        correlationId = requireText(correlationId, "Correlation id is required");
    }

    public PaymentInstructionRequest request() {
        return new PaymentInstructionRequest(idempotencyKey, amount, currency, description);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
