# Payment Instruction State Events Design

**Issue:** `.github#250` under `.github#31`

## Goal

Publish durable, versioned payment-instruction state events for `PENDING`,
`COMPLETED`, and `FAILED` without changing account or ledger state, adding
provider integrations, or adding public payment routes.

## Contract

The organization contract will be added in a separate `.github` pull request.
The service publishes one Kafka topic:

```text
payment.instruction.state.v1
```

Every message uses the event type `PaymentInstructionStateChanged.v1` and the
following JSON payload envelope:

```json
{
  "eventId": "uuid",
  "eventType": "PaymentInstructionStateChanged.v1",
  "schemaVersion": "1.0.0",
  "producer": "payment-service",
  "occurredAt": "2026-09-06T19:00:00Z",
  "aggregateId": "payment-instruction-uuid",
  "correlationId": "trace-or-workflow-id",
  "causationId": "stable-command-or-transition-id",
  "instructionId": "payment-instruction-uuid",
  "idempotencyKey": "client-idempotency-key",
  "amount": "125.50",
  "currency": "AED",
  "status": "PENDING",
  "failureReason": null
}
```

`failureReason` is present only for `FAILED` events. Required Kafka headers use
kebab-case and repeat `event-id`, `correlation-id`, `causation-id`, `producer`,
`schema-version`, and `occurred-at`. `aggregateId` is the Kafka partition key.
`eventId` is generated once for a state transition, persisted with the outbox
payload, and reused for every delivery retry.

The existing lifecycle API does not carry a causal command identifier. Until a
dedicated command-header contract is introduced, creation uses the normalized
idempotency key as `causationId`; a terminal transition uses
`<instructionId>:<status>`. The instruction's existing correlation ID is
preserved unchanged.

## Persistence And Atomicity

Flyway adds `payment_instruction_outbox` with one row per instruction state,
enforced by a unique `(instruction_id, event_status)` constraint. The row
stores the serialized JSON payload plus the common header values and publisher
control data: attempts, next-attempt time, lease expiry, publication time, and
last error. `PENDING`, `COMPLETED`, and `FAILED` are business event statuses;
publication status is represented by `published_at` and retry metadata.

`PaymentInstructionService` owns the application transaction. On creation it
persists the instruction and PENDING outbox record together. On a real terminal
transition it updates the instruction and inserts exactly one terminal outbox
record together. Idempotent replays return the existing state and do not create
another outbox row. A failed outbox insert rolls back the instruction mutation.

## Publication

The publisher is a Spring scheduled adapter backed by `KafkaTemplate<String,
String>`. It claims at most the configured batch size using PostgreSQL row locks
and a time-bounded lease, increments the attempt count before sending, and
publishes the stored payload and headers. Success sets `published_at`; failure
sets `last_error` and a bounded retry time, or leaves the row durably marked
failed once `max-attempts` is reached. Lease expiry makes an abandoned claim
eligible again, preserving at-least-once delivery. A crash after Kafka accepts
the record and before the success update may duplicate delivery, but never
creates a second business transition and always reuses the same event ID.

## Testing And Scope

Focused tests cover the application transaction/outbox behavior against the
existing PostgreSQL Testcontainers pattern and publisher success/retry/final
failure using a test sender. Existing API, unit, Maven, and Helm checks remain
the quality gates. No account, ledger, provider credential, public route,
redundant CI job, or direct synchronous downstream call is introduced.
