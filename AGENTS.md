# Payment Service Agent Guide

## Purpose

`payment-service` is the foundation for future payment workflow and payment rail integration boundaries. It is intentionally a bootstrap service until payment business behavior is separately planned and tracked.

## Current Boundaries

- Owns service bootstrap, Config Client, health probes, OpenAPI metadata, packaging, and deployment.
- Owns the transport-neutral payment instruction lifecycle foundation and its idempotency/correlation boundary.
- Owns durable payment instruction persistence, the transactional payment state outbox, and bounded Kafka publication.
- Does not own customer data, account balances, ledger postings, transfer saga orchestration, provider integrations, or secrets.
- Do not add public payment routes without a supporting issue and API contract work.

## Commands

```bash
./mvnw test
./mvnw verify
docker build -t digital-bank-java/payment-service:<tag> .
helm lint helm --strict --values helm/values-sit.yaml
```

## Runtime

- Service port: `8085`.
- Config name: `payment-service`.
- SIT namespace: `digital-bank-sit`.
- Runtime profiles: `sit`, `uat`, and `prod`; `local` is retired.
- Runtime configuration is externalized through Config Server and `config-repo`.

## Testing

Use unit tests for isolated application/domain behavior when those layers exist. Use integration tests for HTTP and infrastructure-backed behavior. Keep `./mvnw verify` green before merge.

The payment instruction lifecycle uses PostgreSQL in production and an in-memory adapter in unit tests. The transactional state outbox and Kafka publisher are part of the current payment instruction event slice; external payment-provider integration requires separately tracked work.

## Architecture

Use hexagonal boundaries for future payment workflows. Inbound web adapters should handle transport only; outbound adapters should isolate payment providers and other infrastructure. Never place business logic in controllers, Helm templates, or CI workflows.

## Workflow

Every change requires a supporting GitHub issue, a dedicated branch, and a non-draft pull request. Assign active tasks to `ramioooz`, use the native Issue Type, attach work to the correct sprint/epic, and never merge directly to `main`.
