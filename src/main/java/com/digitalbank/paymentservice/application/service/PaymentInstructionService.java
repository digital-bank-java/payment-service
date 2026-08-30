package com.digitalbank.paymentservice.application.service;

import com.digitalbank.paymentservice.application.port.in.CompletePaymentInstructionInputPort;
import com.digitalbank.paymentservice.application.port.in.CreatePaymentInstructionCommand;
import com.digitalbank.paymentservice.application.port.in.CreatePaymentInstructionInputPort;
import com.digitalbank.paymentservice.application.port.in.FailPaymentInstructionInputPort;
import com.digitalbank.paymentservice.application.port.in.PaymentInstructionResult;
import com.digitalbank.paymentservice.application.port.out.PaymentInstructionRepository;
import com.digitalbank.paymentservice.domain.exception.PaymentInstructionIdempotencyConflictException;
import com.digitalbank.paymentservice.domain.model.PaymentInstruction;
import com.digitalbank.paymentservice.domain.model.PaymentInstructionId;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class PaymentInstructionService
        implements CreatePaymentInstructionInputPort,
                CompletePaymentInstructionInputPort,
                FailPaymentInstructionInputPort {

    private final PaymentInstructionRepository repository;
    private final Clock clock;

    public PaymentInstructionService(PaymentInstructionRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "Payment instruction repository is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Override
    public PaymentInstructionResult register(CreatePaymentInstructionCommand command) {
        Objects.requireNonNull(command, "Payment instruction command is required");
        var request = command.request();
        var candidate = PaymentInstruction.open(
                PaymentInstructionId.generate(), request, command.correlationId(), clock.instant());
        var saved = repository.saveIfAbsent(candidate);

        if (!saved.inserted()) {
            if (!saved.instruction().request().equals(request)) {
                throw new PaymentInstructionIdempotencyConflictException(request.idempotencyKey());
            }
            return PaymentInstructionResult.from(saved.instruction(), true);
        }

        return PaymentInstructionResult.from(saved.instruction(), false);
    }

    @Override
    public PaymentInstructionResult complete(PaymentInstructionId instructionId) {
        return PaymentInstructionResult.from(
                repository.transition(instructionId, instruction -> instruction.complete(clock.instant())), false);
    }

    @Override
    public PaymentInstructionResult fail(PaymentInstructionId instructionId, String reason) {
        return PaymentInstructionResult.from(
                repository.transition(instructionId, instruction -> instruction.fail(reason, clock.instant())), false);
    }
}
