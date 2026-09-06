package com.digitalbank.paymentservice.application.service;

import com.digitalbank.paymentservice.application.port.out.PaymentInstructionStateEvent;
import com.digitalbank.paymentservice.domain.model.PaymentInstruction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PaymentInstructionStateEventFactory {

    static final String EVENT_TYPE = "PaymentInstructionStateChanged.v1";
    static final String SCHEMA_VERSION = "1.0.0";
    static final String PRODUCER = "payment-service";

    private final ObjectMapper objectMapper;

    public PaymentInstructionStateEventFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PaymentInstructionStateEvent create(PaymentInstruction instruction) {
        var eventId = UUID.randomUUID();
        var occurredAt = instruction.updatedAt();
        var aggregateId = instruction.id().value().toString();
        var status = instruction.status().name();
        var causationId =
                status.equals("PENDING") ? instruction.request().idempotencyKey() : aggregateId + ":" + status;
        var amount = instruction.request().amount().toPlainString();
        var payload = payload(eventId, occurredAt, aggregateId, instruction, causationId, amount, status);
        var headers = Map.of(
                "event-id",
                eventId.toString(),
                "correlation-id",
                instruction.correlationId(),
                "causation-id",
                causationId,
                "producer",
                PRODUCER,
                "schema-version",
                SCHEMA_VERSION,
                "occurred-at",
                occurredAt.toString());
        return new PaymentInstructionStateEvent(
                eventId,
                EVENT_TYPE,
                SCHEMA_VERSION,
                PRODUCER,
                occurredAt,
                aggregateId,
                instruction.correlationId(),
                causationId,
                aggregateId,
                instruction.request().idempotencyKey(),
                amount,
                instruction.request().currency(),
                status,
                instruction.failureReason(),
                payload,
                headers);
    }

    private String payload(
            UUID eventId,
            Instant occurredAt,
            String aggregateId,
            PaymentInstruction instruction,
            String causationId,
            String amount,
            String status) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("eventId", eventId);
        payload.put("eventType", EVENT_TYPE);
        payload.put("schemaVersion", SCHEMA_VERSION);
        payload.put("producer", PRODUCER);
        payload.put("occurredAt", occurredAt);
        payload.put("aggregateId", aggregateId);
        payload.put("correlationId", instruction.correlationId());
        payload.put("causationId", causationId);
        payload.put("instructionId", aggregateId);
        payload.put("idempotencyKey", instruction.request().idempotencyKey());
        payload.put("amount", amount);
        payload.put("currency", instruction.request().currency());
        payload.put("status", status);
        if (instruction.failureReason() != null) {
            payload.put("failureReason", instruction.failureReason());
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize payment instruction state event", exception);
        }
    }
}
