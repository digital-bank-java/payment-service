package com.digitalbank.paymentservice.application.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PaymentInstructionOutbox {

    void save(PaymentInstructionStateEvent event);

    List<PaymentInstructionOutboxRecord> claimBatch(Instant now, int batchSize, Duration lease);

    void markPublished(UUID eventId, UUID claimId, Instant publishedAt);

    void markFailedOrRetry(
            UUID eventId,
            UUID claimId,
            int attempts,
            int maxAttempts,
            Instant now,
            Duration retryBackoff,
            String error);
}
