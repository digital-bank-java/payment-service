package com.digitalbank.paymentservice.adapter.out.persistence;

import com.digitalbank.paymentservice.application.port.out.PaymentInstructionRepository;
import com.digitalbank.paymentservice.application.port.out.PaymentInstructionSaveResult;
import com.digitalbank.paymentservice.domain.exception.PaymentInstructionNotFoundException;
import com.digitalbank.paymentservice.domain.model.PaymentInstruction;
import com.digitalbank.paymentservice.domain.model.PaymentInstructionId;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

public class InMemoryPaymentInstructionRepository implements PaymentInstructionRepository {

    private final ConcurrentHashMap<PaymentInstructionId, PaymentInstruction> instructions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PaymentInstructionId> idsByIdempotencyKey = new ConcurrentHashMap<>();

    @Override
    public Optional<PaymentInstruction> findById(PaymentInstructionId instructionId) {
        return Optional.ofNullable(instructions.get(instructionId));
    }

    @Override
    public synchronized PaymentInstructionSaveResult saveIfAbsent(PaymentInstruction instruction) {
        var existingId = idsByIdempotencyKey.get(instruction.request().idempotencyKey());
        if (existingId != null) {
            return new PaymentInstructionSaveResult(instructions.get(existingId), false);
        }

        instructions.put(instruction.id(), instruction);
        idsByIdempotencyKey.put(instruction.request().idempotencyKey(), instruction.id());
        return new PaymentInstructionSaveResult(instruction, true);
    }

    @Override
    public PaymentInstruction save(PaymentInstruction instruction) {
        instructions.put(instruction.id(), instruction);
        return instruction;
    }

    @Override
    public PaymentInstruction transition(
            PaymentInstructionId instructionId, UnaryOperator<PaymentInstruction> transition) {
        var updated = instructions.compute(instructionId, (ignored, current) -> {
            if (current == null) {
                return null;
            }
            return transition.apply(current);
        });
        return Optional.ofNullable(updated).orElseThrow(() -> new PaymentInstructionNotFoundException(instructionId));
    }
}
