# Payment Service Agent Guide

## Purpose

`payment-service` is the foundation for future payment workflow and payment rail integration boundaries. It is intentionally a bootstrap service until payment business behavior is separately planned and tracked.

## Current Boundaries

- Owns service bootstrap, Config Client, health probes, OpenAPI metadata, packaging, and deployment.
- Does not own customer data, account balances, ledger postings, transfer saga orchestration, Kafka behavior, persistence, or secrets.
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

## Architecture

Use hexagonal boundaries for future payment workflows. Inbound web adapters should handle transport only; outbound adapters should isolate payment providers and other infrastructure. Never place business logic in controllers, Helm templates, or CI workflows.

## Workflow

Every change requires a supporting GitHub issue, a dedicated branch, and a non-draft pull request. Assign active tasks to `ramioooz`, use the native Issue Type, attach work to the correct sprint/epic, and never merge directly to `main`.
