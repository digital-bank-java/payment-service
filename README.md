# Payment Service

Payment Service is the Digital Bank Java platform foundation for future payment rail workflows. This repository contains a deployable Spring Boot service plus an authenticated internal payment instruction lifecycle API; payment rail business logic, Kafka behavior, and public gateway routes remain intentionally out of scope.

## Implemented State

- Java 21 Spring Boot service named `payment-service`.
- Spring Cloud Config Client integration for externalized runtime configuration.
- Actuator health, liveness, and readiness probes.
- Explicit internal OpenAPI metadata at `/v3/api-docs`.
- Non-root container image and hardened Helm deployment.
- Default SIT service port `8085`.
- Internal payment instruction lifecycle endpoints at `/internal/v1/payment-instructions`.

## Boundaries

This service will later own payment workflow coordination and payment rail integration boundaries. It does not currently own customer data, account balances, ledger postings, transfer saga orchestration, Kafka topics, or provider credentials.

Payment rail integrations must remain behind outbound ports and adapters when that work is approved and tracked. Do not add provider credentials or payment data to this repository.

## Payment Instruction Lifecycle API

The application exposes an internal HTTP adapter over the transport-neutral payment instruction lifecycle boundary. A payment instruction is accepted as `PENDING` and can move once to either `COMPLETED` or `FAILED`; repeating the same create request returns the original instruction as an idempotent replay, repeating the same terminal outcome is idempotent, and changing a terminal outcome is rejected.

The application boundary also normalizes the idempotency key, amount, currency, and description before comparing retries. An equivalent retry returns the original instruction identity, while reuse of the same idempotency key with a different business request is rejected. Correlation IDs are retained for tracing and are deliberately excluded from the idempotency comparison, so a retried request may have a new trace context.

Payment instructions are stored in PostgreSQL through a Flyway-managed schema. Idempotency keys are unique in the database, so equivalent retries converge across replicas and restarts; the canonical request fields are compared before a replay is returned, and a changed payload returns `409 Conflict`. Payment-provider adapters, Kafka publication, and transaction saga orchestration are intentionally deferred to their planned stories.

All payment instruction endpoints require a bearer JWT with the `payment.internal` scope. Missing or invalid authentication returns `401 Unauthorized`; an authenticated caller without that scope returns `403 Forbidden`. Both responses use `application/problem+json`. Health, service metadata, and the generated OpenAPI document remain public.

### Endpoints

- `POST /internal/v1/payment-instructions` creates a payment instruction and returns `201 Created` with `Location: /internal/v1/payment-instructions/{instructionId}`.
- Repeating an equivalent create request returns `200 OK` with `Idempotent-Replay: true`.
- `GET /internal/v1/payment-instructions/{instructionId}` retrieves the stable payment instruction representation, or returns `404 Not Found` when the instruction does not exist.
- `POST /internal/v1/payment-instructions/{instructionId}/completion` completes a payment instruction.
- `POST /internal/v1/payment-instructions/{instructionId}/failure` fails a payment instruction with a required JSON body containing `reason`.

Error responses use `application/problem+json` for boundary validation failures, including malformed instruction ids, unknown instruction ids, conflicting create-time idempotency keys, invalid lifecycle transitions, and security failures. The generated internal contract is available at `/v3/api-docs`.

## Runtime Configuration

Config Server supplies the effective runtime configuration. The service repository contains only the Config Client bootstrap:

| Variable | Purpose | Default |
| --- | --- | --- |
| `CONFIG_SERVER_URL` | Config Server base URL | `http://localhost:8888` |
| `SPRING_PROFILES_ACTIVE` | Runtime environment profile | Spring `default` profile |
| `SERVER_PORT` | HTTP listen port | `8085` |
| `spring.datasource.url` | PostgreSQL JDBC URL | Required from Config Server or Helm deployment values |
| `spring.datasource.username` | PostgreSQL username | Required from Config Server or the referenced Kubernetes Secret |
| `spring.datasource.password` | PostgreSQL password | Required from Config Server or the referenced Kubernetes Secret |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | OIDC issuer URI | Optional when using the SIT HMAC contract |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | JWT JWK set URI | Optional when issuer discovery is available |
| `auth.jwt.secret` | Base64 HMAC secret shared with Auth Service in SIT | none |
| `auth.jwt.issuer` | HMAC token issuer used in SIT | none |

The application fallback port is `8085`, and the Helm chart sets `SERVER_PORT` from `service.port` so the process, probes, and Service remain aligned even before a service-specific Config Repo entry is added. The SIT chart injects the `payment_service` JDBC URL and reads PostgreSQL credentials from the existing `postgres` Secret (`POSTGRES_USER` and `POSTGRES_PASSWORD`). Credentials remain outside Git.

When `spring.security.oauth2.resourceserver.jwt.issuer-uri` is configured, Payment Service uses OIDC discovery or the explicit JWK set. In local SIT, it instead uses `auth.jwt.secret` and `auth.jwt.issuer` to validate the shared Auth Service HMAC token. HMAC mode requires a base64 secret decoding to at least 32 bytes; JWK material alone is not treated as sufficient trust configuration.

The formal environments are `sit`, `uat`, and `prod`. `sit` runs on local Docker Desktop Kubernetes; `uat` and `prod` are future AWS environments. `local` is not an active environment or Spring profile. Workstation debugging uses the `sit` profile with temporary overrides against forwarded SIT dependencies.

## Prerequisites

- Java 21.
- Network access to Maven Central for the initial dependency download.
- Docker Desktop for image builds.
- Helm 4 and Docker Desktop Kubernetes for local SIT deployment.
- A healthy Config Server for normal application startup.

The Maven Wrapper is included, so a global Maven installation is not required.

```bash
java -version
./mvnw --version
docker version
helm version --short
kubectl config current-context
```

## Build And Test

Run the unit-test phase:

```bash
./mvnw --batch-mode --no-transfer-progress test
```

Run integration tests and package verification:

```bash
./mvnw --batch-mode --no-transfer-progress verify -DskipUnitTests=true
```

Socket-level integration tests disable Config Client, start PostgreSQL with Testcontainers, and validate health, OpenAPI metadata, authenticated payment instruction HTTP behavior, duplicate/replay/conflict handling, and concurrent retries using a random application port. Focused controller tests use in-process `MockMvc` to cover request binding and Problem Details mapping.

## Run With Docker

Build the image:

```bash
docker build -t digital-bank-java/payment-service:0.0.2 .
```

Run it against a reachable Config Server:

```bash
docker run --rm \
  --name digital-bank-payment-service \
  --publish 8085:8085 \
  --env CONFIG_SERVER_URL=http://host.docker.internal:8888 \
  --env SPRING_PROFILES_ACTIVE=sit \
  digital-bank-java/payment-service:0.0.2
```

The image runs as numeric non-root user and group `10001:10001` and uses `/tmp` for writable temporary files.

## Deploy To Local SIT

The Helm chart deploys into the `digital-bank-sit` namespace and expects Config Server to be available at `http://config-server:8888`.

```bash
helm lint helm --strict --values helm/values-sit.yaml

helm template payment-service helm \
  --namespace digital-bank-sit \
  --values helm/values-sit.yaml \
  --set image.tag="0.0.2" \
  | kubectl apply --dry-run=client -f -

helm upgrade --install payment-service helm \
  --namespace digital-bank-sit \
  --create-namespace \
  --values helm/values-sit.yaml \
  --wait \
  --timeout 5m
```

Check the rollout and service health:

```bash
kubectl rollout status deployment/payment-service \
  --namespace digital-bank-sit --timeout=180s

kubectl port-forward service/payment-service 18085:8085 \
  --namespace digital-bank-sit
```

In another terminal:

```bash
curl --fail http://localhost:18085/actuator/health
curl --fail http://localhost:18085/v3/api-docs
curl --fail -X POST http://localhost:18085/internal/v1/payment-instructions \
  -H "Authorization: Bearer $PAYMENT_INTERNAL_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"payment-001","correlationId":"trace-001","amount":25.00,"currency":"USD","description":"Utility bill payment"}'
```

Normal platform access should later flow through the API Gateway. The current HTTP surface is internal-only; payment-provider integrations and external routing are still out of scope for this repository.

## CI

The GitHub Actions workflow runs Maven verification and Helm validation. A container job then builds the image, verifies the non-root user, starts disposable PostgreSQL and Config Server fixtures, and smoke-tests health. Third-party actions are pinned to immutable commit SHAs.

## Workstation Debugging Against SIT

Use the organization [workstation debugging procedure](https://github.com/digital-bank-java/.github/blob/main/docs/workstation-debugging-against-sit.md). Confirm SIT dependencies first, temporarily scale down the Kubernetes payment deployment, use the `sit` profile, and provide temporary Config Server overrides through environment variables. Restore the deployment after debugging.

## Contribution Workflow

Use a dedicated branch and pull request; do not commit directly to `main`. Before opening a PR:

```bash
git status
./mvnw verify
git diff --check
```

All changes require review by the CODEOWNERS maintainer. Never commit credentials, tokens, customer information, payment data, or production endpoints.
