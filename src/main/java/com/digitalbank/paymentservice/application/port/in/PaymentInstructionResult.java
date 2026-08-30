package com.digitalbank.paymentservice.application.port.in;

import com.digitalbank.paymentservice.domain.model.PaymentInstruction;
import com.digitalbank.paymentservice.domain.model.PaymentInstructionId;
import com.digitalbank.paymentservice.domain.model.PaymentInstructionStatus;
import java.math.BigDecimal;

public record PaymentInstructionResult(
        PaymentInstructionId instructionId,
        String correlationId,
        BigDecimal amount,
        String currency,
        PaymentInstructionStatus status,
        String failureReason,
        boolean idempotentReplay) {

    public static PaymentInstructionResult from(PaymentInstruction instruction, boolean idempotentReplay) {
        return new PaymentInstructionResult(
                instruction.id(),
                instruction.correlationId(),
                instruction.request().amount(),
                instruction.request().currency(),
                instruction.status(),
                instruction.failureReason(),
                idempotentReplay);
    }
}
