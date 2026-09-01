package com.digitalbank.paymentservice.adapter.in.web;

import com.digitalbank.paymentservice.application.port.in.CreatePaymentInstructionCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

@Schema(name = "CreatePaymentInstructionRequest", description = "Request to create a payment instruction.")
record CreatePaymentInstructionRequest(
        @Schema(description = "Client-supplied key used to make retries safe.", example = "payment-20260831-0001")
        @NotBlank(message = "must not be blank")
        String idempotencyKey,

        @Schema(
                description = "Identifier used to correlate this instruction across services.",
                example = "correlation-20260831-0001")
        @NotBlank(message = "must not be blank")
        String correlationId,

        @Schema(description = "Positive payment amount in the specified currency.", example = "125.50")
        @NotNull(message = "must not be null")
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than 0")
        BigDecimal amount,

        @Schema(description = "Three-letter currency code.", example = "AED", pattern = "[A-Za-z]{3}")
        @NotBlank(message = "must not be blank")
        @Pattern(regexp = "[A-Za-z]{3}", message = "must be a three-letter currency code")
        String currency,

        @Schema(description = "Optional human-readable payment description.", example = "Utility bill payment")
        String description) {

    CreatePaymentInstructionCommand toCommand() {
        return new CreatePaymentInstructionCommand(idempotencyKey, correlationId, amount, currency, description);
    }
}
