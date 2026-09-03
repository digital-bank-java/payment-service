package com.digitalbank.paymentservice.domain.exception;

import com.digitalbank.paymentservice.domain.model.PaymentInstructionId;

public class PaymentInstructionNotFoundException extends RuntimeException {

    private final PaymentInstructionId instructionId;

    public PaymentInstructionNotFoundException(PaymentInstructionId id) {
        super("Payment instruction was not found: " + id.value());
        this.instructionId = id;
    }

    public PaymentInstructionId instructionId() {
        return instructionId;
    }
}
