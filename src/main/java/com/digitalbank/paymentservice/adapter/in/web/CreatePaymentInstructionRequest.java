package com.digitalbank.paymentservice.adapter.in.web;

import com.digitalbank.paymentservice.application.port.in.CreatePaymentInstructionCommand;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

record CreatePaymentInstructionRequest(
        @NotBlank(message = "must not be blank") String idempotencyKey,
        @NotBlank(message = "must not be blank") String correlationId,

        @NotNull(message = "must not be null")
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than 0")
        BigDecimal amount,

        @NotBlank(message = "must not be blank")
        @Pattern(regexp = "[A-Za-z]{3}", message = "must be a three-letter currency code")
        String currency,

        String description) {

    CreatePaymentInstructionCommand toCommand() {
        return new CreatePaymentInstructionCommand(idempotencyKey, correlationId, amount, currency, description);
    }
}
