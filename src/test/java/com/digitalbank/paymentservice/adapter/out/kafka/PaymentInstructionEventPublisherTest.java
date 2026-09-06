package com.digitalbank.paymentservice.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.digitalbank.paymentservice.application.port.out.PaymentInstructionOutbox;
import com.digitalbank.paymentservice.application.port.out.PaymentInstructionOutboxRecord;
import com.digitalbank.paymentservice.application.port.out.PaymentInstructionStateEvent;
import com.digitalbank.paymentservice.configuration.PaymentEventProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class PaymentInstructionEventPublisherTest {

    private static final Instant NOW = Instant.parse("2026-09-06T19:00:00Z");

    @Test
    void publishesClaimedEventWithStableContractHeadersAndBoundedBatch() {
        var outbox = new TestOutbox(List.of(record(0)));
        var kafkaTemplate = new RecordingKafkaTemplate();
        var properties = new PaymentEventProperties();
        properties.setBatchSize(1);
        var publisher = new PaymentInstructionEventPublisher(
                outbox, kafkaTemplate, properties, Clock.fixed(NOW, ZoneOffset.UTC));

        publisher.publishBatch();

        var sent = kafkaTemplate.records.getFirst();
        assertThat(sent.topic()).isEqualTo("payment.instruction.state.v1");
        assertThat(sent.key()).isEqualTo(outbox.event.aggregateId());
        assertThat(sent.value()).isEqualTo(outbox.event.payload());
        assertThat(sent.headers().lastHeader("event-id").value())
                .isEqualTo(outbox.event.eventId().toString().getBytes());
        assertThat(sent.headers().lastHeader("correlation-id").value()).isEqualTo("correlation-001".getBytes());
        assertThat(sent.headers().lastHeader("causation-id").value()).isEqualTo("command-001".getBytes());
        assertThat(sent.headers().lastHeader("producer").value()).isEqualTo("payment-service".getBytes());
        assertThat(sent.headers().lastHeader("schema-version").value()).isEqualTo("1.0.0".getBytes());
        assertThat(sent.headers().lastHeader("occurred-at").value())
                .isEqualTo(NOW.toString().getBytes());
        assertThat(outbox.published).containsExactly(outbox.event.eventId());
    }

    @Test
    void retriesBrokerFailureAndMarksTheEventFailedAfterTheBoundedAttempts() {
        var first = record(0);
        var second = new PaymentInstructionOutboxRecord(first.event(), 1, UUID.randomUUID(), NOW.plusSeconds(30));
        var outbox = new TestOutbox(List.of(first, second));
        var kafkaTemplate = new RecordingKafkaTemplate();
        kafkaTemplate.failure = new IllegalStateException("broker unavailable");
        var properties = new PaymentEventProperties();
        properties.setMaxAttempts(2);
        properties.setRetryBackoff(Duration.ZERO);
        var publisher = new PaymentInstructionEventPublisher(
                outbox, kafkaTemplate, properties, Clock.fixed(NOW, ZoneOffset.UTC));

        publisher.publishBatch();
        publisher.publishBatch();

        assertThat(outbox.failureAttempts).containsExactly(1, 2);
        assertThat(outbox.failureMaxAttempts).containsExactly(2, 2);
        assertThat(outbox.failureErrors).allMatch(error -> error.contains("broker unavailable"));
        assertThat(kafkaTemplate.records).hasSize(2).allSatisfy(sent -> assertThat(sent.value())
                .isEqualTo(first.event().payload()));
        assertThat(kafkaTemplate.records)
                .extracting(sent -> sent.headers().lastHeader("event-id").value())
                .containsOnly(first.event().eventId().toString().getBytes());
    }

    private static PaymentInstructionOutboxRecord record(int attempts) {
        var id = UUID.randomUUID();
        var event = new PaymentInstructionStateEvent(
                id,
                "PaymentInstructionStateChanged.v1",
                "1.0.0",
                "payment-service",
                NOW,
                "instruction-001",
                "correlation-001",
                "command-001",
                "instruction-001",
                "idempotency-001",
                "10.00",
                "USD",
                "PENDING",
                null,
                "{\"eventId\":\"" + id + "\"}",
                Map.of(
                        "event-id", id.toString(),
                        "correlation-id", "correlation-001",
                        "causation-id", "command-001",
                        "producer", "payment-service",
                        "schema-version", "1.0.0",
                        "occurred-at", NOW.toString()));
        return new PaymentInstructionOutboxRecord(event, attempts, UUID.randomUUID(), NOW.plusSeconds(30));
    }

    private static final class TestOutbox implements PaymentInstructionOutbox {

        private final PaymentInstructionStateEvent event;
        private final List<PaymentInstructionOutboxRecord> records;
        private final List<UUID> published = new ArrayList<>();
        private final List<Integer> failureAttempts = new ArrayList<>();
        private final List<Integer> failureMaxAttempts = new ArrayList<>();
        private final List<String> failureErrors = new ArrayList<>();
        private int claims;

        private TestOutbox(List<PaymentInstructionOutboxRecord> records) {
            this.records = records;
            this.event = records.get(0).event();
        }

        @Override
        public void save(PaymentInstructionStateEvent event) {}

        @Override
        public List<PaymentInstructionOutboxRecord> claimBatch(Instant now, int batchSize, Duration lease) {
            return claims < records.size() ? List.of(records.get(claims++)) : List.of();
        }

        @Override
        public void markPublished(UUID eventId, UUID claimId, Instant publishedAt) {
            published.add(eventId);
        }

        @Override
        public void markFailedOrRetry(
                UUID eventId,
                UUID claimId,
                int attempts,
                int maxAttempts,
                Instant now,
                Duration retryBackoff,
                String error) {
            failureAttempts.add(attempts);
            failureMaxAttempts.add(maxAttempts);
            failureErrors.add(error);
        }
    }

    private static final class RecordingKafkaTemplate extends KafkaTemplate<String, String> {

        private final List<ProducerRecord<String, String>> records = new ArrayList<>();
        private RuntimeException failure;

        private RecordingKafkaTemplate() {
            super(new DefaultKafkaProducerFactory<>(Map.of("bootstrap.servers", "unused:9092")));
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(ProducerRecord<String, String> record) {
            records.add(record);
            return failure == null ? CompletableFuture.completedFuture(null) : CompletableFuture.failedFuture(failure);
        }
    }
}
