# Payment Instruction State Events Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a transactional PostgreSQL outbox and bounded at-least-once Kafka publisher for payment-instruction state events.

**Architecture:** Extend the existing application service transaction to persist one immutable event payload for each accepted instruction state. Keep the outbox behind an outbound port, use JDBC/Flyway for PostgreSQL, and isolate Kafka delivery in a scheduled adapter with leased batch claims and durable retry state.

**Tech Stack:** Java 21, Spring Boot 4, Spring JDBC transactions, Flyway PostgreSQL migrations, Spring Kafka, Jackson, JUnit 5, AssertJ, Testcontainers PostgreSQL.

**Spec:** `docs/superpowers/specs/2026-09-06-payment-instruction-state-events-design.md`

## Global Constraints

- Use topic `payment.instruction.state.v1` and event type `PaymentInstructionStateChanged.v1`.
- Payload status is exactly `PENDING`, `COMPLETED`, or `FAILED`.
- Reuse the persisted `eventId` and serialized payload on every retry.
- Create and terminal transition plus outbox insert must share one database transaction.
- Do not mutate accounts or ledger, add provider credentials, add public routes, or add extra CI jobs.
- Keep tests focused on load-bearing persistence and publication behavior.
- Add the organization AsyncAPI contract in a separate `.github` PR; do not embed a conflicting schema in this service.

---

### Task 1: Add the event and outbox application boundaries

**Files:**
- Create: `src/main/java/com/digitalbank/paymentservice/application/port/out/PaymentInstructionStateEvent.java`
- Create: `src/main/java/com/digitalbank/paymentservice/application/port/out/PaymentInstructionOutbox.java`
- Create: `src/main/java/com/digitalbank/paymentservice/application/port/out/PaymentInstructionStateEventFactory.java`
- Modify: `src/main/java/com/digitalbank/paymentservice/application/port/out/PaymentInstructionRepository.java`
- Modify: `src/main/java/com/digitalbank/paymentservice/application/port/out/PaymentInstructionSaveResult.java`
- Test: `src/test/java/com/digitalbank/paymentservice/application/service/PaymentInstructionServiceTest.java`

**Interfaces:**
- `PaymentInstructionStateEvent` exposes `eventId`, `eventType`, `schemaVersion`, `producer`, `occurredAt`, `aggregateId`, `correlationId`, `causationId`, `instructionId`, `idempotencyKey`, `amount`, `currency`, `status`, and nullable `failureReason`, plus serialized payload and header values.
- `PaymentInstructionOutbox.save(PaymentInstructionStateEvent event)` is the transactional outbound port; the JDBC adapter implements it later.
- `PaymentInstructionRepository.saveIfAbsent` accepts the instruction and a PENDING event; `transition` returns a result that distinguishes a real state change from an idempotent replay.

- [ ] **Step 1: Write the failing service tests** for one PENDING event on a new instruction, one terminal event on a real transition, and no duplicate event on equivalent create or repeated terminal replay.
- [ ] **Step 2: Run the focused test** with `./mvnw --batch-mode --no-transfer-progress -Dtest=PaymentInstructionServiceTest test`; confirm it fails because the new event/outbox contract is absent.
- [ ] **Step 3: Implement the immutable event record, event factory, and repository transition result** using the approved envelope and deterministic causation fallback.
- [ ] **Step 4: Update the service transaction** to build an event once per real mutation and call the outbox port only after the corresponding instruction write succeeds in the same transaction.
- [ ] **Step 5: Run the focused test** again and confirm all service tests pass.
- [ ] **Step 6: Commit** with `git add src/main/java src/test/java && git commit -m "feat: define payment instruction state events"`.

### Task 2: Add PostgreSQL outbox persistence and atomic integration

**Files:**
- Create: `src/main/resources/db/migration/V2__create_payment_instruction_outbox.sql`
- Create: `src/main/java/com/digitalbank/paymentservice/adapter/out/persistence/PostgresPaymentInstructionOutbox.java`
- Create: `src/main/java/com/digitalbank/paymentservice/adapter/out/persistence/PaymentInstructionOutboxRecord.java`
- Modify: `src/main/java/com/digitalbank/paymentservice/adapter/out/persistence/PostgresPaymentInstructionRepository.java`
- Modify: `src/test/java/com/digitalbank/paymentservice/PaymentInstructionApiIT.java`

**Interfaces:**
- The adapter implements `PaymentInstructionOutbox` and provides `claimBatch`, `markPublished`, and `markFailedOrRetry` for the publisher.
- The migration creates immutable event identity and payload columns, a unique `(instruction_id, event_status)` key, and indexed eligible-row lookup.

- [ ] **Step 1: Write the failing PostgreSQL integration assertions** that creation leaves exactly one PENDING outbox row with matching instruction fields and that completion/failure leaves exactly one matching terminal row.
- [ ] **Step 2: Run `./mvnw --batch-mode --no-transfer-progress -Dtest=PaymentInstructionApiIT test`** and confirm the new SQL assertions fail because the table/adapter is absent.
- [ ] **Step 3: Implement the Flyway migration** with check constraints for event and publication fields, unique identity, and retry/lease indexes.
- [ ] **Step 4: Implement the JDBC outbox adapter** with serialized payload storage, PostgreSQL `FOR UPDATE SKIP LOCKED` claims, leases, and durable publication/error updates.
- [ ] **Step 5: Wire the existing JDBC instruction repository and service transaction** so a failed outbox insert rolls back the instruction mutation.
- [ ] **Step 6: Run the focused integration test** and confirm atomic creation, terminal transition, and replay behavior pass.
- [ ] **Step 7: Commit** with `git add src/main src/test && git commit -m "feat: persist payment instruction outbox events"`.

### Task 3: Add bounded Kafka publication

**Files:**
- Create: `src/main/java/com/digitalbank/paymentservice/adapter/out/kafka/PaymentInstructionEventPublisher.java`
- Create: `src/main/java/com/digitalbank/paymentservice/configuration/PaymentEventProperties.java`
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/java/com/digitalbank/paymentservice/PaymentServiceApplication.java`
- Test: `src/test/java/com/digitalbank/paymentservice/adapter/out/kafka/PaymentInstructionEventPublisherTest.java`

**Interfaces:**
- `PaymentInstructionEventPublisher.publishBatch()` claims at most `batch-size` eligible records, sends each `ProducerRecord<String,String>` to the configured topic, and marks each outcome through the outbox adapter.
- Kafka record key is `aggregateId`; required headers are `event-id`, `correlation-id`, `causation-id`, `producer`, `schema-version`, and `occurred-at`.
- Defaults are topic `payment.instruction.state.v1`, batch size `25`, max attempts `5`, fixed retry backoff `PT5S`, and lease `PT30S`; all are externalizable under `payment.events.*`.

- [ ] **Step 1: Write the failing publisher tests** for successful publication with exact topic/key/headers/payload, retry after a sender failure, and durable final failure at max attempts.
- [ ] **Step 2: Run `./mvnw --batch-mode --no-transfer-progress -Dtest=PaymentInstructionEventPublisherTest test`** and confirm it fails because the publisher is absent.
- [ ] **Step 3: Add `spring-kafka`, typed properties, scheduling enablement, and safe defaults** without adding provider or Kafka credentials to the repository.
- [ ] **Step 4: Implement the publisher** with bounded claim size, blocking send result handling, lease-safe updates, and bounded error text.
- [ ] **Step 5: Run the focused publisher test** and confirm all publication cases pass.
- [ ] **Step 6: Commit** with `git add pom.xml src/main src/test && git commit -m "feat: publish payment instruction state events"`.

### Task 4: Document operations and verify the whole service

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Test: existing Maven integration suite and Helm chart

- [ ] **Step 1: Add concise operational documentation** for the topic, outbox retry visibility, externalized publisher settings, and SIT evidence query; state that account/ledger/provider/public-route behavior remains out of scope.
- [ ] **Step 2: Run `./mvnw --batch-mode --no-transfer-progress test`**.
- [ ] **Step 3: Run `./mvnw --batch-mode --no-transfer-progress verify -DskipUnitTests=true`** for integration/package verification.
- [ ] **Step 4: Run `helm lint helm --strict --values helm/values-sit.yaml` and render the chart with `helm template`**.
- [ ] **Step 5: Run `git diff --check` and scan the diff for forbidden account/ledger mutations, credentials, public routes, and CI changes.**
- [ ] **Step 6: Commit** with `git add README.md AGENTS.md && git commit -m "docs: document payment state event operations"`.
- [ ] **Step 7: Create the separate `.github` contract worktree/PR** from `.github` `origin/main`, add the exact approved AsyncAPI contract, run its existing validation, push, and open a normal non-draft PR. Link it explicitly from the service PR.
- [ ] **Step 8: Request review of the service diff, fix Critical/Important findings, then push the service branch and open a normal non-draft PR linked to `.github#250` and `.github#31`; do not merge either PR.
