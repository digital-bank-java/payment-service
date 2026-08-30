package com.digitalbank.paymentservice.domain.exception;

public class PaymentInstructionIdempotencyConflictException extends RuntimeException {

    private final String idempotencyKey;

    public PaymentInstructionIdempotencyConflictException(String idempotencyKey) {
        super("Idempotency key was already used with a different payment request: " + idempotencyKey);
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
