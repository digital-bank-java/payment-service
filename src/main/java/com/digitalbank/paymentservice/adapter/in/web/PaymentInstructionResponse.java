package com.digitalbank.paymentservice.adapter.in.web;

import com.digitalbank.paymentservice.application.port.in.PaymentInstructionResult;
import com.digitalbank.paymentservice.domain.model.PaymentInstructionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(
        name = "PaymentInstructionResponse",
        description = "Payment instruction resource returned by the internal payment API.")
record PaymentInstructionResponse(
        @Schema(description = "Stable identifier of the payment instruction.", format = "uuid")
        UUID instructionId,

        @Schema(description = "Correlation identifier supplied by the caller.", example = "correlation-20260831-0001")
        String correlationId,

        @Schema(description = "Payment amount in the specified currency.", example = "125.50")
        BigDecimal amount,

        @Schema(description = "Three-letter currency code.", example = "AED", pattern = "[A-Z]{3}")
        String currency,

        @Schema(description = "Current payment instruction lifecycle state.", example = "PENDING")
        PaymentInstructionStatus status,

        @Schema(description = "Failure reason when the instruction has FAILED.", example = "Provider rejected payment.")
        String failureReason,

        @Schema(
                description = "True when the create request replayed an equivalent existing instruction.",
                example = "false")
        boolean idempotentReplay) {

    static PaymentInstructionResponse from(PaymentInstructionResult result) {
        return new PaymentInstructionResponse(
                result.instructionId().value(),
                result.correlationId(),
                result.amount(),
                result.currency(),
                result.status(),
                result.failureReason(),
                result.idempotentReplay());
    }
}
