package com.digitalbank.paymentservice.adapter.out.kafka;

import com.digitalbank.paymentservice.application.port.out.PaymentInstructionOutbox;
import com.digitalbank.paymentservice.application.port.out.PaymentInstructionOutboxRecord;
import com.digitalbank.paymentservice.configuration.PaymentEventProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentInstructionEventPublisher {

    private final PaymentInstructionOutbox outbox;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PaymentEventProperties properties;
    private final Clock clock;

    public PaymentInstructionEventPublisher(
            PaymentInstructionOutbox outbox,
            KafkaTemplate<String, String> kafkaTemplate,
            PaymentEventProperties properties,
            Clock clock) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${payment.events.poll-delay-ms:1000}")
    public void publishScheduled() {
        publishBatch();
    }

    public void publishBatch() {
        var now = clock.instant();
        var claimed = outbox.claimBatch(now, properties.getBatchSize(), properties.getLease());
        for (var record : claimed) {
            publish(record);
        }
    }

    private void publish(PaymentInstructionOutboxRecord record) {
        if (record.attempts() > properties.getMaxAttempts()) {
            var event = record.event();
            outbox.markFailedOrRetry(
                    event.eventId(),
                    record.claimId(),
                    record.attempts(),
                    properties.getMaxAttempts(),
                    clock.instant(),
                    properties.getRetryBackoff(),
                    "Maximum payment event publication attempts exceeded");
            return;
        }
        try {
            var event = record.event();
            var kafkaRecord =
                    new ProducerRecord<String, String>(properties.getTopic(), event.aggregateId(), event.payload());
            addHeaders(kafkaRecord, event.headers());
            kafkaTemplate.send(kafkaRecord).get(properties.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            outbox.markPublished(event.eventId(), record.claimId(), clock.instant());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            markFailure(record, exception);
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            markFailure(record, exception);
        }
    }

    private void markFailure(PaymentInstructionOutboxRecord record, Throwable exception) {
        var event = record.event();
        outbox.markFailedOrRetry(
                event.eventId(),
                record.claimId(),
                record.attempts(),
                properties.getMaxAttempts(),
                clock.instant(),
                properties.getRetryBackoff(),
                errorMessage(exception));
    }

    private static void addHeaders(ProducerRecord<String, String> record, Map<String, String> headers) {
        headers.forEach((name, value) -> record.headers().add(name, value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String errorMessage(Throwable exception) {
        var cause = exception instanceof ExecutionException && exception.getCause() != null
                ? exception.getCause()
                : exception;
        var message = cause.getMessage();
        var value = cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}
