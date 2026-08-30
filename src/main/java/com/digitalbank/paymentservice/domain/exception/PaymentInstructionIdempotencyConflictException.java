package com.digitalbank.paymentservice.domain.exception;

public class PaymentInstructionIdempotencyConflictException extends RuntimeException {

    public PaymentInstructionIdempotencyConflictException(String idempotencyKey) {
        super("Idempotency key was already used with a different payment request: " + idempotencyKey);
    }
}
