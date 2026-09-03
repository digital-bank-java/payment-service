package com.digitalbank.paymentservice.domain.model;

import java.util.Objects;
import java.util.UUID;

public record PaymentInstructionId(UUID value) {

    public PaymentInstructionId {
        Objects.requireNonNull(value, "Payment instruction id is required");
    }

    public static PaymentInstructionId generate() {
        return new PaymentInstructionId(UUID.randomUUID());
    }
}
