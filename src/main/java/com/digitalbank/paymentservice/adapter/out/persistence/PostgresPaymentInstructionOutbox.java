package com.digitalbank.paymentservice.adapter.out.persistence;

import com.digitalbank.paymentservice.application.port.out.PaymentInstructionOutbox;
import com.digitalbank.paymentservice.application.port.out.PaymentInstructionOutboxRecord;
import com.digitalbank.paymentservice.application.port.out.PaymentInstructionStateEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class PostgresPaymentInstructionOutbox implements PaymentInstructionOutbox {

    private static final String SELECT_COLUMNS = """
            outbox.event_id, outbox.event_type, outbox.schema_version, outbox.producer, outbox.occurred_at, outbox.aggregate_id,
            outbox.correlation_id, outbox.causation_id, outbox.instruction_id, outbox.idempotency_key, outbox.amount, outbox.currency,
            outbox.event_status, outbox.failure_reason, outbox.payload::text as payload, outbox.attempt_count, outbox.claim_id, outbox.claim_until
            """;

    private final JdbcTemplate jdbcTemplate;

    PostgresPaymentInstructionOutbox(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void save(PaymentInstructionStateEvent event) {
        jdbcTemplate.update(
                """
                insert into payment_instruction_outbox (
                    event_id, instruction_id, aggregate_id, event_type, schema_version, producer,
                    occurred_at, correlation_id, causation_id, idempotency_key, amount, currency,
                    event_status, failure_reason, payload, next_attempt_at, created_at
) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as numeric), ?, ?, ?, ?::jsonb, ?, ?)
                """,
                event.eventId(),
                UUID.fromString(event.instructionId()),
                UUID.fromString(event.aggregateId()),
                event.eventType(),
                event.schemaVersion(),
                event.producer(),
                Timestamp.from(event.occurredAt()),
                event.correlationId(),
                event.causationId(),
                event.idempotencyKey(),
                event.amount(),
                event.currency(),
                event.status(),
                event.failureReason(),
                event.payload(),
                Timestamp.from(event.occurredAt()),
                Timestamp.from(event.occurredAt()));
    }

    @Override
    @Transactional
    public List<PaymentInstructionOutboxRecord> claimBatch(Instant now, int batchSize, Duration lease) {
        var claimId = UUID.randomUUID();
        var claimUntil = now.plus(lease);
        return jdbcTemplate.query(
                """
                with candidates as (
                    select event_id
                      from payment_instruction_outbox
                     where publication_status = 'PENDING'
                       and next_attempt_at <= ?
                       and (claim_until is null or claim_until < ?)
                     order by created_at, event_id
                     for update skip locked
                     limit ?
                )
                update payment_instruction_outbox outbox
                   set claim_id = ?, claim_until = ?
                  from candidates
                 where outbox.event_id = candidates.event_id
                returning
                """ + SELECT_COLUMNS,
                this::mapClaimed,
                Timestamp.from(now),
                Timestamp.from(now),
                batchSize,
                claimId,
                Timestamp.from(claimUntil));
    }

    @Override
    @Transactional
    public void markPublished(UUID eventId, UUID claimId, Instant publishedAt) {
        jdbcTemplate.update("""
                update payment_instruction_outbox
                   set publication_status = 'PUBLISHED',
                       attempt_count = attempt_count + 1,
                       published_at = ?,
                       claim_id = null,
                       claim_until = null
                 where event_id = ?
                   and claim_id = ?
                   and publication_status = 'PENDING'
                """, Timestamp.from(publishedAt), eventId, claimId);
    }

    @Override
    @Transactional
    public void markFailedOrRetry(
            UUID eventId,
            UUID claimId,
            int attempts,
            int maxAttempts,
            Instant now,
            Duration retryBackoff,
            String error) {
        var terminal = attempts >= maxAttempts;
        jdbcTemplate.update(
                """
                update payment_instruction_outbox
                   set publication_status = ?,
                       attempt_count = ?,
                       next_attempt_at = ?,
                       last_error = ?,
                       claim_id = null,
                       claim_until = null
                 where event_id = ?
                   and claim_id = ?
                   and publication_status = 'PENDING'
                """,
                terminal ? "FAILED" : "PENDING",
                attempts,
                Timestamp.from(terminal ? now : now.plus(retryBackoff)),
                error,
                eventId,
                claimId);
    }

    private PaymentInstructionOutboxRecord mapClaimed(ResultSet resultSet, int rowNumber) throws SQLException {
        var eventId = resultSet.getObject("event_id", UUID.class);
        var claimId = resultSet.getObject("claim_id", UUID.class);
        var event = new PaymentInstructionStateEvent(
                eventId,
                resultSet.getString("event_type"),
                resultSet.getString("schema_version"),
                resultSet.getString("producer"),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getObject("aggregate_id", UUID.class).toString(),
                resultSet.getString("correlation_id"),
                resultSet.getString("causation_id"),
                resultSet.getObject("instruction_id", UUID.class).toString(),
                resultSet.getString("idempotency_key"),
                resultSet.getBigDecimal("amount").toPlainString(),
                resultSet.getString("currency"),
                resultSet.getString("event_status"),
                resultSet.getString("failure_reason"),
                resultSet.getString("payload"),
                Map.of(
                        "event-id", eventId.toString(),
                        "correlation-id", resultSet.getString("correlation_id"),
                        "causation-id", resultSet.getString("causation_id"),
                        "producer", resultSet.getString("producer"),
                        "schema-version", resultSet.getString("schema_version"),
                        "occurred-at",
                                resultSet
                                        .getTimestamp("occurred_at")
                                        .toInstant()
                                        .toString()));
        return new PaymentInstructionOutboxRecord(
                event,
                resultSet.getInt("attempt_count"),
                claimId,
                resultSet.getTimestamp("claim_until").toInstant());
    }
}
