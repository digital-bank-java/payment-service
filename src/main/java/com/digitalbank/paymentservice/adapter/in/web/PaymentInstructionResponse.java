package com.digitalbank.paymentservice.adapter.in.web;

import com.digitalbank.paymentservice.application.port.in.PaymentInstructionResult;
import com.digitalbank.paymentservice.domain.model.PaymentInstructionStatus;
import java.math.BigDecimal;
import java.util.UUID;

record PaymentInstructionResponse(
        UUID instructionId,
        String correlationId,
        BigDecimal amount,
        String currency,
        PaymentInstructionStatus status,
        String failureReason,
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
