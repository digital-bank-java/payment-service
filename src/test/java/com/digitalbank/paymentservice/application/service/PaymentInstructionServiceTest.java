package com.digitalbank.paymentservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.digitalbank.paymentservice.adapter.out.persistence.InMemoryPaymentInstructionRepository;
import com.digitalbank.paymentservice.application.port.in.CreatePaymentInstructionCommand;
import com.digitalbank.paymentservice.application.port.in.PaymentInstructionResult;
import com.digitalbank.paymentservice.domain.exception.PaymentInstructionIdempotencyConflictException;
import com.digitalbank.paymentservice.domain.exception.PaymentInstructionNotFoundException;
import com.digitalbank.paymentservice.domain.model.PaymentInstructionId;
import com.digitalbank.paymentservice.domain.model.PaymentInstructionStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PaymentInstructionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-30T10:15:30Z");

    private final PaymentInstructionService service =
            new PaymentInstructionService(new InMemoryPaymentInstructionRepository(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void repeatedEquivalentRequestReturnsOriginalInstructionAsReplay() {
        var command = command("payment-001", "USD", "100.00");

        var first = service.register(command);
        var replay = service.register(command(" payment-001 ", "usd", "100.0"));

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.instructionId()).isEqualTo(first.instructionId());
        assertThat(replay.status()).isEqualTo(PaymentInstructionStatus.PENDING);
        assertThat(replay.correlationId()).isEqualTo("correlation-001");
    }

    @Test
    void retryWithDifferentCorrelationIdStillReplaysTheSameBusinessRequest() {
        var first = service.register(command("payment-001", "USD", "100.00"));
        var retry = service.register(new CreatePaymentInstructionCommand(
                "payment-001", "correlation-retry", new BigDecimal("100.00"), "USD", "customer payment"));

        assertThat(retry.idempotentReplay()).isTrue();
        assertThat(retry.instructionId()).isEqualTo(first.instructionId());
        assertThat(retry.correlationId()).isEqualTo(first.correlationId());
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsRejected() {
        service.register(command("payment-001", "USD", "100.00"));

        assertThatThrownBy(() -> service.register(command("payment-001", "USD", "101.00")))
                .isInstanceOf(PaymentInstructionIdempotencyConflictException.class);
    }

    @Test
    void completionIsTerminalAndIdempotent() {
        var created = service.register(command("payment-001", "USD", "100.00"));

        var completed = service.complete(created.instructionId());
        var repeatedCompletion = service.complete(created.instructionId());

        assertThat(completed.status()).isEqualTo(PaymentInstructionStatus.COMPLETED);
        assertThat(repeatedCompletion).isEqualTo(completed);
    }

    @Test
    void failureIsTerminalAndIdempotent() {
        var created = service.register(command("payment-001", "USD", "100.00"));

        var failed = service.fail(created.instructionId(), "provider rejected payment");
        var repeatedFailure = service.fail(created.instructionId(), "provider rejected payment");

        assertThat(failed.status()).isEqualTo(PaymentInstructionStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo("provider rejected payment");
        assertThat(repeatedFailure).isEqualTo(failed);
    }

    @Test
    void terminalInstructionCannotTransitionToAnotherTerminalState() {
        var created = service.register(command("payment-001", "USD", "100.00"));
        service.complete(created.instructionId());

        assertThatThrownBy(() -> service.fail(created.instructionId(), "late failure"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED");
    }

    @Test
    void missingInstructionCannotBeCompleted() {
        assertThatThrownBy(() -> service.complete(new com.digitalbank.paymentservice.domain.model.PaymentInstructionId(
                        java.util.UUID.randomUUID())))
                .isInstanceOf(PaymentInstructionNotFoundException.class);
    }

    @Test
    void concurrentEquivalentRequestsCreateOneInstruction() throws Exception {
        var command = command("payment-concurrent", "EUR", "25");
        ExecutorService executor = Executors.newFixedThreadPool(8);

        try {
            List<Future<PaymentInstructionResult>> futures = IntStream.range(0, 20)
                    .mapToObj(index -> executor.submit(() -> service.register(command)))
                    .toList();
            List<PaymentInstructionResult> results =
                    futures.stream().map(this::get).toList();

            assertThat(results).allMatch(result -> result.instructionId()
                    .equals(results.get(0).instructionId()));
            assertThat(results.stream()
                            .filter(result -> !result.idempotentReplay())
                            .count())
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentCompletionAndFailureAllowOnlyOneTerminalTransition() throws Exception {
        var repository = new CoordinatedTransitionRepository();
        var racingService = new PaymentInstructionService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        var created = racingService.register(command("payment-race", "USD", "10.00"));
        repository.coordinate(created.instructionId());
        var barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Object> completion =
                    executor.submit(compete(barrier, () -> racingService.complete(created.instructionId())));
            Future<Object> failure = executor.submit(
                    compete(barrier, () -> racingService.fail(created.instructionId(), "provider rejected payment")));

            assertThat(repository.awaitConcurrentTransitions()).isTrue();
            repository.releaseTransitions();

            List<Object> results = List.of(completion.get(), failure.get());

            assertThat(results)
                    .filteredOn(PaymentInstructionResult.class::isInstance)
                    .hasSize(1);
            assertThat(results).filteredOn(Throwable.class::isInstance).hasSize(1);

            var success = (PaymentInstructionResult) results.stream()
                    .filter(PaymentInstructionResult.class::isInstance)
                    .findFirst()
                    .orElseThrow();
            var error = (Throwable) results.stream()
                    .filter(Throwable.class::isInstance)
                    .findFirst()
                    .orElseThrow();

            assertThat(success.status()).isIn(PaymentInstructionStatus.COMPLETED, PaymentInstructionStatus.FAILED);
            assertThat(error).isInstanceOf(IllegalStateException.class);
            assertThat(error).hasMessageContaining(success.status().name());
        } finally {
            executor.shutdownNow();
        }
    }

    private PaymentInstructionResult get(Future<PaymentInstructionResult> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private Callable<Object> compete(CyclicBarrier barrier, Callable<PaymentInstructionResult> action) {
        return () -> {
            barrier.await();
            try {
                return action.call();
            } catch (Exception exception) {
                return exception;
            }
        };
    }

    private static CreatePaymentInstructionCommand command(String key, String currency, String amount) {
        return new CreatePaymentInstructionCommand(
                key, "correlation-001", new BigDecimal(amount), currency, "customer payment");
    }

    private static final class CoordinatedTransitionRepository extends InMemoryPaymentInstructionRepository {

        private volatile PaymentInstructionId coordinatedInstructionId;
        private volatile CountDownLatch concurrentTransitions;
        private volatile CountDownLatch releaseTransitions;

        void coordinate(PaymentInstructionId instructionId) {
            coordinatedInstructionId = instructionId;
            concurrentTransitions = new CountDownLatch(2);
            releaseTransitions = new CountDownLatch(1);
        }

        boolean awaitConcurrentTransitions() {
            try {
                return concurrentTransitions.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }

        void releaseTransitions() {
            releaseTransitions.countDown();
        }

        @Override
        public com.digitalbank.paymentservice.domain.model.PaymentInstruction transition(
                PaymentInstructionId instructionId,
                java.util.function.UnaryOperator<com.digitalbank.paymentservice.domain.model.PaymentInstruction>
                        transition) {
            awaitConcurrentTransition(instructionId);
            return super.transition(instructionId, transition);
        }

        private void awaitConcurrentTransition(PaymentInstructionId instructionId) {
            var entered = concurrentTransitions;
            var release = releaseTransitions;
            if (entered == null
                    || release == null
                    || !instructionId.equals(coordinatedInstructionId)
                    || entered.getCount() <= 0) {
                return;
            }
            entered.countDown();
            try {
                if (!release.await(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release coordinated transitions");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }
    }
}
