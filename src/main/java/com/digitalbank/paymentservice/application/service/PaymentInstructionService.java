package com.digitalbank.paymentservice.application.service;

import com.digitalbank.paymentservice.application.port.in.CompletePaymentInstructionInputPort;
import com.digitalbank.paymentservice.application.port.in.CreatePaymentInstructionCommand;
import com.digitalbank.paymentservice.application.port.in.CreatePaymentInstructionInputPort;
import com.digitalbank.paymentservice.application.port.in.FailPaymentInstructionInputPort;
import com.digitalbank.paymentservice.application.port.in.GetPaymentInstructionInputPort;
import com.digitalbank.paymentservice.application.port.in.PaymentInstructionResult;
import com.digitalbank.paymentservice.application.port.out.PaymentInstructionOutbox;
import com.digitalbank.paymentservice.application.port.out.PaymentInstructionRepository;
import com.digitalbank.paymentservice.domain.exception.PaymentInstructionIdempotencyConflictException;
import com.digitalbank.paymentservice.domain.exception.PaymentInstructionNotFoundException;
import com.digitalbank.paymentservice.domain.model.PaymentInstruction;
import com.digitalbank.paymentservice.domain.model.PaymentInstructionId;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentInstructionService
        implements CreatePaymentInstructionInputPort,
                GetPaymentInstructionInputPort,
                CompletePaymentInstructionInputPort,
                FailPaymentInstructionInputPort {

    private final PaymentInstructionRepository repository;
    private final PaymentInstructionOutbox outbox;
    private final PaymentInstructionStateEventFactory eventFactory;
    private final Clock clock;

    public PaymentInstructionService(
            PaymentInstructionRepository repository,
            Clock clock,
            PaymentInstructionOutbox outbox,
            PaymentInstructionStateEventFactory eventFactory) {
        this.repository = Objects.requireNonNull(repository, "Payment instruction repository is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.outbox = Objects.requireNonNull(outbox, "Payment instruction outbox is required");
        this.eventFactory = Objects.requireNonNull(eventFactory, "Payment instruction state event factory is required");
    }

    @Override
    @Transactional
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

        outbox.save(eventFactory.create(saved.instruction()));
        return PaymentInstructionResult.from(saved.instruction(), false);
    }

    @Override
    public PaymentInstructionResult get(PaymentInstructionId instructionId) {
        Objects.requireNonNull(instructionId, "Payment instruction id is required");
        return repository
                .findById(instructionId)
                .map(instruction -> PaymentInstructionResult.from(instruction, false))
                .orElseThrow(() -> new PaymentInstructionNotFoundException(instructionId));
    }

    @Override
    @Transactional
    public PaymentInstructionResult complete(PaymentInstructionId instructionId) {
        var transition = repository.transition(instructionId, instruction -> instruction.complete(clock.instant()));
        if (transition.transitioned()) {
            outbox.save(eventFactory.create(transition.instruction()));
        }
        return PaymentInstructionResult.from(transition.instruction(), false);
    }

    @Override
    @Transactional
    public PaymentInstructionResult fail(PaymentInstructionId instructionId, String reason) {
        var transition = repository.transition(instructionId, instruction -> instruction.fail(reason, clock.instant()));
        if (transition.transitioned()) {
            outbox.save(eventFactory.create(transition.instruction()));
        }
        return PaymentInstructionResult.from(transition.instruction(), false);
    }
}
