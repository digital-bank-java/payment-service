# Payment Service

Payment Service is the Digital Bank Java platform foundation for future payment rail workflows. This repository currently contains only a deployable Spring Boot baseline; payment business logic, Kafka behavior, and public routes are intentionally out of scope.

## Implemented State

- Java 21 Spring Boot service named `payment-service`.
- Spring Cloud Config Client integration for externalized runtime configuration.
- Actuator health, liveness, and readiness probes.
- Explicit internal OpenAPI metadata at `/v3/api-docs`.
- Non-root container image and hardened Helm deployment.
- Default SIT service port `8085`.

## Boundaries

This service will later own payment workflow coordination and payment rail integration boundaries. It does not currently own customer data, account balances, ledger postings, transfer saga orchestration, Kafka topics, persistence, or secrets.

Payment rail integrations must remain behind outbound ports and adapters when that work is approved and tracked. Do not add provider credentials or payment data to this repository.

## Runtime Configuration

Config Server supplies the effective runtime configuration. The service repository contains only the Config Client bootstrap:

| Variable | Purpose | Default |
| --- | --- | --- |
| `CONFIG_SERVER_URL` | Config Server base URL | `http://localhost:8888` |
| `SPRING_PROFILES_ACTIVE` | Runtime environment profile | Spring `default` profile |

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

The integration test disables Config Client and validates health plus the OpenAPI title and contract version using a random application port.

## Run With Docker

Build the image:

```bash
docker build -t digital-bank-java/payment-service:0.0.1 .
```

Run it against a reachable Config Server:

```bash
docker run --rm \
  --name digital-bank-payment-service \
  --publish 8085:8085 \
  --env CONFIG_SERVER_URL=http://host.docker.internal:8888 \
  --env SPRING_PROFILES_ACTIVE=sit \
  digital-bank-java/payment-service:0.0.1
```

The image runs as numeric non-root user and group `10001:10001` and uses `/tmp` for writable temporary files.

## Deploy To Local SIT

The Helm chart deploys into the `digital-bank-sit` namespace and expects Config Server to be available at `http://config-server:8888`.

```bash
helm lint helm --strict --values helm/values-sit.yaml

helm template payment-service helm \
  --namespace digital-bank-sit \
  --values helm/values-sit.yaml \
  --set image.tag="0.0.1" \
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
```

Normal platform access should later flow through the API Gateway. No payment business route is exposed by this bootstrap.

## CI

The GitHub Actions workflow runs Maven verification and Helm validation. A container job then builds the image, verifies the non-root user, and smoke-tests health against a disposable mock Config Server. Third-party actions are pinned to immutable commit SHAs.

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
