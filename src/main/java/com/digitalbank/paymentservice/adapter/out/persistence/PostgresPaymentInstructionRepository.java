package com.digitalbank.paymentservice.adapter.out.persistence;

import com.digitalbank.paymentservice.application.port.out.PaymentInstructionRepository;
import com.digitalbank.paymentservice.application.port.out.PaymentInstructionSaveResult;
import com.digitalbank.paymentservice.domain.exception.PaymentInstructionNotFoundException;
import com.digitalbank.paymentservice.domain.model.PaymentInstruction;
import com.digitalbank.paymentservice.domain.model.PaymentInstructionId;
import com.digitalbank.paymentservice.domain.model.PaymentInstructionRequest;
import com.digitalbank.paymentservice.domain.model.PaymentInstructionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class PostgresPaymentInstructionRepository implements PaymentInstructionRepository {

    private static final String SELECT_COLUMNS = """
            id, idempotency_key, correlation_id, amount, currency, description,
            status, failure_reason, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    PostgresPaymentInstructionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<PaymentInstruction> findById(PaymentInstructionId instructionId) {
        return queryOne("select " + SELECT_COLUMNS + " from payment_instructions where id = ?", instructionId.value());
    }

    @Override
    @Transactional
    public PaymentInstructionSaveResult saveIfAbsent(PaymentInstruction instruction) {
        var inserted = jdbcTemplate.update(
                """
                insert into payment_instructions (
                    id, idempotency_key, correlation_id, amount, currency, description,
                    status, failure_reason, version, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (idempotency_key) do nothing
                """,
                instruction.id().value(),
                instruction.request().idempotencyKey(),
                instruction.correlationId(),
                instruction.request().amount(),
                instruction.request().currency(),
                instruction.request().description(),
                instruction.status().name(),
                instruction.failureReason(),
                0L,
                Timestamp.from(instruction.createdAt()),
                Timestamp.from(instruction.updatedAt()));

        if (inserted == 1) {
            return new PaymentInstructionSaveResult(instruction, true);
        }

        var existing = findByIdempotencyKey(instruction.request().idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("Payment instruction insert conflict was not readable"));
        return new PaymentInstructionSaveResult(existing, false);
    }

    @Override
    @Transactional
    public PaymentInstruction save(PaymentInstruction instruction) {
        var updated = jdbcTemplate.update(
                """
                update payment_instructions
                   set idempotency_key = ?, correlation_id = ?, amount = ?, currency = ?, description = ?,
                       status = ?, failure_reason = ?, version = ?, created_at = ?, updated_at = ?
                 where id = ?
                """,
                instruction.request().idempotencyKey(),
                instruction.correlationId(),
                instruction.request().amount(),
                instruction.request().currency(),
                instruction.request().description(),
                instruction.status().name(),
                instruction.failureReason(),
                0L,
                Timestamp.from(instruction.createdAt()),
                Timestamp.from(instruction.updatedAt()),
                instruction.id().value());
        if (updated == 0) {
            saveIfAbsent(instruction);
        }
        return instruction;
    }

    @Override
    @Transactional
    public PaymentInstruction transition(
            PaymentInstructionId instructionId, UnaryOperator<PaymentInstruction> transition) {
        var current = queryOne(
                        "select " + SELECT_COLUMNS + " from payment_instructions where id = ? for update",
                        instructionId.value())
                .orElseThrow(() -> new PaymentInstructionNotFoundException(instructionId));
        var updated = transition.apply(current);
        if (updated.equals(current)) {
            return current;
        }

        jdbcTemplate.update(
                """
                update payment_instructions
                   set status = ?, failure_reason = ?, version = version + 1, updated_at = ?
                 where id = ?
                """,
                updated.status().name(),
                updated.failureReason(),
                Timestamp.from(updated.updatedAt()),
                instructionId.value());
        return updated;
    }

    private Optional<PaymentInstruction> findByIdempotencyKey(String idempotencyKey) {
        return queryOne(
                "select " + SELECT_COLUMNS + " from payment_instructions where idempotency_key = ?", idempotencyKey);
    }

    private Optional<PaymentInstruction> queryOne(String sql, Object parameter) {
        return jdbcTemplate.query(sql, this::map, parameter).stream().findFirst();
    }

    private PaymentInstruction map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new PaymentInstruction(
                new PaymentInstructionId(UUID.fromString(resultSet.getString("id"))),
                new PaymentInstructionRequest(
                        resultSet.getString("idempotency_key"),
                        resultSet.getBigDecimal("amount"),
                        resultSet.getString("currency"),
                        resultSet.getString("description")),
                resultSet.getString("correlation_id"),
                PaymentInstructionStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("failure_reason"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }
}
