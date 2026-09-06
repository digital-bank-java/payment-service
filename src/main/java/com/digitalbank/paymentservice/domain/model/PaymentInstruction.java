package com.digitalbank.paymentservice.domain.model;

import java.time.Instant;
import java.util.Objects;

public record PaymentInstruction(
        PaymentInstructionId id,
        PaymentInstructionRequest request,
        String correlationId,
        PaymentInstructionStatus status,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public PaymentInstruction {
        Objects.requireNonNull(id, "Payment instruction id is required");
        Objects.requireNonNull(request, "Payment instruction request is required");
        correlationId = requireText(correlationId, "Correlation id is required");
        Objects.requireNonNull(status, "Payment instruction status is required");
        Objects.requireNonNull(createdAt, "Creation time is required");
        Objects.requireNonNull(updatedAt, "Update time is required");
        if (status == PaymentInstructionStatus.FAILED) {
            failureReason = requireText(failureReason, "Failure reason is required");
        } else if (failureReason != null) {
            throw new IllegalArgumentException("Only failed instructions may have a failure reason");
        }
    }

    public static PaymentInstruction open(
            PaymentInstructionId id, PaymentInstructionRequest request, String correlationId, Instant now) {
        return new PaymentInstruction(id, request, correlationId, PaymentInstructionStatus.PENDING, null, now, now);
    }

    public PaymentInstruction complete(Instant now) {
        if (status == PaymentInstructionStatus.COMPLETED) {
            return this;
        }
        requirePending();
        return new PaymentInstruction(
                id, request, correlationId, PaymentInstructionStatus.COMPLETED, null, createdAt, now);
    }

    public PaymentInstruction fail(String reason, Instant now) {
        if (status == PaymentInstructionStatus.FAILED) {
            return this;
        }
        requirePending();
        reason = requireFailureReason(reason);
        return new PaymentInstruction(
                id, request, correlationId, PaymentInstructionStatus.FAILED, reason, createdAt, now);
    }

    private void requirePending() {
        if (status != PaymentInstructionStatus.PENDING) {
            throw new IllegalStateException("Payment instruction is already " + status);
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String requireFailureReason(String value) {
        var reason = requireText(value, "Failure reason is required");
        if (reason.length() > 500) {
            throw new IllegalArgumentException("Failure reason must be at most 500 characters");
        }
        return reason;
    }
}
