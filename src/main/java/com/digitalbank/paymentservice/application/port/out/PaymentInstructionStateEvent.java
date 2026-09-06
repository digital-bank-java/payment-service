package com.digitalbank.paymentservice.application.port.out;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PaymentInstructionStateEvent(
        UUID eventId,
        String eventType,
        String schemaVersion,
        String producer,
        Instant occurredAt,
        String aggregateId,
        String correlationId,
        String causationId,
        String instructionId,
        String idempotencyKey,
        String amount,
        String currency,
        String status,
        String failureReason,
        String payload,
        Map<String, String> headers) {}
