package com.digitalbank.paymentservice.application.port.out;

import java.time.Instant;
import java.util.UUID;

public record PaymentInstructionOutboxRecord(
        PaymentInstructionStateEvent event, int attempts, UUID claimId, Instant claimUntil) {}
