package com.digitalbank.paymentservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.digitalbank.paymentservice.application.port.in.CreatePaymentInstructionCommand;
import com.digitalbank.paymentservice.application.port.out.PaymentInstructionRepository;
import com.digitalbank.paymentservice.application.service.PaymentInstructionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSecurityConfig.class)
class PaymentInstructionApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @LocalServerPort
    private int port;

    @Autowired
    private PaymentInstructionService paymentInstructionService;

    @Autowired
    private PaymentInstructionRepository paymentInstructionRepository;

    @Test
    void createsPaymentInstructionAndReplaysEquivalentRequest() throws Exception {
        var requestBody = """
                {
                  "idempotencyKey": "payment-http-001",
                  "correlationId": "correlation-http-001",
                  "amount": 125.50,
                  "currency": "aed",
                  "description": "Utility bill payment"
                }
                """;

        var created = sendJson("POST", "/internal/v1/payment-instructions", requestBody);
        var replay = sendJson("POST", "/internal/v1/payment-instructions", requestBody);

        assertThat(created.statusCode()).isEqualTo(201);
        assertContentType(created, "application/json");
        assertThat(created.headers().firstValue("location"))
                .hasValueSatisfying(location -> assertThat(location).contains("/internal/v1/payment-instructions/"));
        var createdBody = read(created.body());
        assertThat(createdBody.path("instructionId").asText()).isNotBlank();
        assertThat(createdBody.path("status").asText()).isEqualTo("PENDING");
        assertThat(createdBody.path("currency").asText()).isEqualTo("AED");
        assertThat(createdBody.path("idempotentReplay").asBoolean()).isFalse();

        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replay")).hasValue("true");
        var replayBody = read(replay.body());
        assertThat(replayBody.path("instructionId").asText())
                .isEqualTo(createdBody.path("instructionId").asText());
        assertThat(replayBody.path("idempotentReplay").asBoolean()).isTrue();
    }

    @Test
    void completesAndFailsPaymentInstructions() throws Exception {
        var completedInstructionId = createInstruction("payment-http-complete");
        var completion = send("POST", "/internal/v1/payment-instructions/" + completedInstructionId + "/completion");

        assertThat(completion.statusCode()).isEqualTo(200);
        assertContentType(completion, "application/json");
        assertThat(read(completion.body()).path("status").asText()).isEqualTo("COMPLETED");

        var failedInstructionId = createInstruction("payment-http-fail");
        var failure = sendJson("POST", "/internal/v1/payment-instructions/" + failedInstructionId + "/failure", """
                {
                  "reason": "provider rejected payment"
                }
                """);

        assertThat(failure.statusCode()).isEqualTo(200);
        assertContentType(failure, "application/json");
        var failed = read(failure.body());
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("failureReason").asText()).isEqualTo("provider rejected payment");
    }

    @Test
    void retrievesCreatedPaymentInstructionUsingLocationResource() throws Exception {
        var instructionId = createInstruction("payment-http-retrieve");

        var retrieved = send("GET", "/internal/v1/payment-instructions/" + instructionId);

        assertThat(retrieved.statusCode()).isEqualTo(200);
        assertContentType(retrieved, "application/json");
        var body = read(retrieved.body());
        assertThat(body.path("instructionId").asText()).isEqualTo(instructionId);
        assertThat(body.path("status").asText()).isEqualTo("PENDING");
        assertThat(body.path("idempotentReplay").asBoolean()).isFalse();

        var missing = send("GET", "/internal/v1/payment-instructions/" + UUID.randomUUID());

        assertThat(missing.statusCode()).isEqualTo(404);
        assertContentType(missing, "application/problem+json");
        assertThat(read(missing.body()).path("type").asText())
                .isEqualTo("https://digital-bank-java.local/problems/payment-instruction-not-found");
    }

    @Test
    void followsTheLocationHeaderReturnedByCreate() throws Exception {
        var created = sendJson("POST", "/internal/v1/payment-instructions", """
                {
                  "idempotencyKey": "payment-http-location-%s",
                  "correlationId": "correlation-location",
                  "amount": 25.00,
                  "currency": "USD",
                  "description": "Location contract"
                }
                """.formatted(UUID.randomUUID()));

        assertThat(created.statusCode()).isEqualTo(201);
        var location = created.headers().firstValue("location").orElseThrow();
        var retrieved = send("GET", location);

        assertThat(retrieved.statusCode()).isEqualTo(200);
        assertThat(read(retrieved.body()).path("instructionId").asText())
                .isEqualTo(read(created.body()).path("instructionId").asText());
    }

    @Test
    void returnsProblemDetailsForLifecycleValidationAndConflicts() throws Exception {
        var invalidCreate = sendJson("POST", "/internal/v1/payment-instructions", """
                {
                  "idempotencyKey": "",
                  "correlationId": "  ",
                  "amount": 0,
                  "currency": "dirham"
                }
                """);

        assertThat(invalidCreate.statusCode()).isEqualTo(400);
        assertContentType(invalidCreate, "application/problem+json");
        var invalidCreateProblem = read(invalidCreate.body());
        assertThat(invalidCreateProblem.path("type").asText())
                .isEqualTo("https://digital-bank-java.local/problems/validation-error");
        assertThat(invalidCreateProblem.path("title").asText()).isEqualTo("Invalid request");
        assertThat(invalidCreateProblem.path("errors")).isNotEmpty();

        var instructionId = createInstruction("payment-http-conflict");
        assertThat(send("POST", "/internal/v1/payment-instructions/" + instructionId + "/completion")
                        .statusCode())
                .isEqualTo(200);
        var conflict = sendJson("POST", "/internal/v1/payment-instructions/" + instructionId + "/failure", """
                {
                  "reason": "late provider error"
                }
                """);

        assertThat(conflict.statusCode()).isEqualTo(409);
        assertContentType(conflict, "application/problem+json");
        var conflictProblem = read(conflict.body());
        assertThat(conflictProblem.path("type").asText())
                .isEqualTo("https://digital-bank-java.local/problems/payment-instruction-state-conflict");
        assertThat(conflictProblem.path("title").asText()).isEqualTo("Payment instruction state conflict");

        var missing = send("POST", "/internal/v1/payment-instructions/" + UUID.randomUUID() + "/completion");

        assertThat(missing.statusCode()).isEqualTo(404);
        assertContentType(missing, "application/problem+json");
        var missingProblem = read(missing.body());
        assertThat(missingProblem.path("type").asText())
                .isEqualTo("https://digital-bank-java.local/problems/payment-instruction-not-found");
    }

    @Test
    void changedPayloadWithSameIdempotencyKeyReturnsDocumentedConflict() throws Exception {
        var idempotencyKey = "payment-http-idempotency-conflict-" + UUID.randomUUID();
        var original = sendJson("POST", "/internal/v1/payment-instructions", """
                {
                  "idempotencyKey": "%s",
                  "correlationId": "correlation-original",
                  "amount": 25.00,
                  "currency": "USD",
                  "description": "Original payment"
                }
                """.formatted(idempotencyKey));
        var changed = sendJson("POST", "/internal/v1/payment-instructions", """
                {
                  "idempotencyKey": "%s",
                  "correlationId": "correlation-retry",
                  "amount": 30.00,
                  "currency": "USD",
                  "description": "Changed payment"
                }
                """.formatted(idempotencyKey));

        assertThat(original.statusCode()).isEqualTo(201);
        assertThat(changed.statusCode()).isEqualTo(409);
        assertContentType(changed, "application/problem+json");
        var problem = read(changed.body());
        assertThat(problem.path("type").asText())
                .isEqualTo("https://digital-bank-java.local/problems/payment-instruction-idempotency-conflict");
        assertThat(problem.path("idempotencyKey").asText()).isEqualTo(idempotencyKey);
    }

    @Test
    void concurrentEquivalentRequestsConvergeToOneDurableInstruction() throws Exception {
        var idempotencyKey = "payment-http-concurrent-" + UUID.randomUUID();
        var requestBody = """
                {
                  "idempotencyKey": "%s",
                  "correlationId": "correlation-concurrent",
                  "amount": 25.00,
                  "currency": "USD",
                  "description": "Concurrent payment"
                }
                """.formatted(idempotencyKey);
        ExecutorService executor = Executors.newFixedThreadPool(8);

        try {
            List<HttpResponse<String>> responses = executor
                    .invokeAll(java.util.stream.IntStream.range(0, 20)
                            .mapToObj(index -> (java.util.concurrent.Callable<HttpResponse<String>>)
                                    () -> sendJson("POST", "/internal/v1/payment-instructions", requestBody))
                            .toList())
                    .stream()
                    .map(this::get)
                    .toList();

            assertThat(responses)
                    .filteredOn(response -> response.statusCode() == 201)
                    .hasSize(1);
            assertThat(responses)
                    .filteredOn(response -> response.statusCode() == 200)
                    .hasSize(19);
            assertThat(responses.stream()
                            .map(response ->
                                    read(response.body()).path("instructionId").asText()))
                    .containsOnly(responses.stream()
                            .map(response ->
                                    read(response.body()).path("instructionId").asText())
                            .findFirst()
                            .orElseThrow());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void equivalentRetryFromFreshServiceInstanceReadsTheDurableInstruction() {
        var key = "payment-fresh-service-" + UUID.randomUUID();
        var command = new CreatePaymentInstructionCommand(
                key, "correlation-first", new BigDecimal("25.00"), "USD", "Durable payment");

        var first = paymentInstructionService.register(command);
        var freshService = new PaymentInstructionService(paymentInstructionRepository, Clock.systemUTC());
        var replay = freshService.register(new CreatePaymentInstructionCommand(
                key, "correlation-retry", new BigDecimal("25.0"), "usd", "Durable payment"));

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.instructionId()).isEqualTo(first.instructionId());
        assertThat(replay.correlationId()).isEqualTo("correlation-first");
    }

    @Test
    void internalPaymentInstructionsRequireAuthenticationAndScope() throws Exception {
        var requestBody = """
                {
                  "idempotencyKey": "payment-http-auth-%s",
                  "correlationId": "correlation-auth",
                  "amount": 25.00,
                  "currency": "USD"
                }
                """.formatted(UUID.randomUUID());

        var unauthenticated = sendJsonWithoutAuthorization("POST", "/internal/v1/payment-instructions", requestBody);
        assertAuthenticationProblem(unauthenticated, "/internal/v1/payment-instructions");

        var insufficient = sendJsonWithAuthorization(
                "POST",
                "/internal/v1/payment-instructions",
                requestBody,
                TestSecurityConfig.INSUFFICIENT_SCOPE_BEARER_TOKEN);
        assertThat(insufficient.statusCode()).isEqualTo(403);
        assertContentType(insufficient, "application/problem+json");
        var problem = read(insufficient.body());
        assertThat(problem.path("type").asText()).isEqualTo("urn:digital-bank:payment:access-denied");
        assertThat(problem.path("title").asText()).isEqualTo("Payment access denied");
        assertThat(problem.path("instance").asText()).isEqualTo("/internal/v1/payment-instructions");
        assertThat(problem.path("detail").asText()).doesNotContain("scope");
    }

    @Test
    void malformedInstructionIdAndFailureContractStayWithinBoundaryRules() throws Exception {
        var malformedId = send("POST", "/internal/v1/payment-instructions/not-a-uuid/completion");

        assertThat(malformedId.statusCode()).isEqualTo(400);
        assertContentType(malformedId, "application/problem+json");
        var malformedIdProblem = read(malformedId.body());
        assertThat(malformedIdProblem.path("type").asText())
                .isEqualTo("https://digital-bank-java.local/problems/validation-error");

        var malformedGetId = send("GET", "/internal/v1/payment-instructions/not-a-uuid");

        assertThat(malformedGetId.statusCode()).isEqualTo(400);
        assertContentType(malformedGetId, "application/problem+json");
        assertThat(read(malformedGetId.body()).path("type").asText())
                .isEqualTo("https://digital-bank-java.local/problems/validation-error");

        var response = send("GET", "/v3/api-docs");
        assertThat(response.statusCode()).isEqualTo(200);
        var document = read(response.body());
        var failureExamples = document.path("paths")
                .path("/internal/v1/payment-instructions/{instructionId}/failure")
                .path("post")
                .path("responses")
                .path("409")
                .path("content")
                .path("application/problem+json")
                .path("examples");

        assertThat(failureExamples.has("payment-instruction-state-conflict")).isTrue();
        assertThat(failureExamples.has("payment-instruction-idempotency-conflict"))
                .isFalse();
    }

    @Test
    void publishesPaymentInstructionContractInOpenApiDocument() throws Exception {
        var response = send("GET", "/v3/api-docs");

        assertThat(response.statusCode()).isEqualTo(200);
        var document = read(response.body());
        assertThat(document.path("paths").has("/internal/v1/payment-instructions"))
                .isTrue();
        var getOperation = document.path("paths")
                .path("/internal/v1/payment-instructions/{instructionId}")
                .path("get");
        assertThat(getOperation.path("responses").has("200")).isTrue();
        assertThat(getOperation.path("responses").has("400")).isTrue();
        assertThat(getOperation.path("responses").has("404")).isTrue();
        assertThat(getOperation.path("responses").path("404").path("content").has("application/problem+json"))
                .isTrue();
        assertThat(document.path("paths")
                        .path("/internal/v1/payment-instructions")
                        .path("post")
                        .path("responses")
                        .has("409"))
                .isTrue();
        assertThat(document.path("paths")
                        .path("/internal/v1/payment-instructions")
                        .path("post")
                        .path("responses")
                        .path("401")
                        .path("content")
                        .has("application/problem+json"))
                .isTrue();
        assertThat(document.path("paths")
                        .path("/internal/v1/payment-instructions")
                        .path("post")
                        .path("responses")
                        .path("403")
                        .path("content")
                        .has("application/problem+json"))
                .isTrue();
        assertThat(document.path("components")
                        .path("securitySchemes")
                        .path("bearer-jwt")
                        .path("scheme")
                        .asText())
                .isEqualTo("bearer");
        assertThat(document.path("paths")
                        .path("/internal/v1/payment-instructions/{instructionId}/failure")
                        .path("post")
                        .path("responses")
                        .has("400"))
                .isTrue();
        assertThat(document.path("paths")
                        .path("/internal/v1/payment-instructions/{instructionId}/completion")
                        .path("post")
                        .path("responses")
                        .has("400"))
                .isTrue();
    }

    @Test
    void documentsEverySupportedPaymentInstructionResponseCode() throws Exception {
        var response = send("GET", "/v3/api-docs");

        assertThat(response.statusCode()).isEqualTo(200);
        var paths = read(response.body()).path("paths");
        assertResponseContract(
                paths.path("/internal/v1/payment-instructions/{instructionId}").path("get"),
                Set.of("200", "400", "401", "403", "404"));
        assertResponseContract(
                paths.path("/internal/v1/payment-instructions").path("post"),
                Set.of("200", "201", "400", "401", "403", "409"));
        assertResponseContract(
                paths.path("/internal/v1/payment-instructions/{instructionId}/completion")
                        .path("post"),
                Set.of("200", "400", "401", "403", "404", "409"));
        assertResponseContract(
                paths.path("/internal/v1/payment-instructions/{instructionId}/failure")
                        .path("post"),
                Set.of("200", "400", "401", "403", "404", "409"));
    }

    @Test
    void documentsResourceAndReplayHeadersForCreateResponses() throws Exception {
        var response = send("GET", "/v3/api-docs");

        assertThat(response.statusCode()).isEqualTo(200);
        var responses = read(response.body())
                .path("paths")
                .path("/internal/v1/payment-instructions")
                .path("post")
                .path("responses");

        assertThat(responses
                        .path("201")
                        .path("headers")
                        .path("Location")
                        .path("schema")
                        .path("type")
                        .asText())
                .isEqualTo("string");
        assertThat(responses
                        .path("200")
                        .path("headers")
                        .path("Location")
                        .path("schema")
                        .path("type")
                        .asText())
                .isEqualTo("string");
        assertThat(responses
                        .path("200")
                        .path("headers")
                        .path("Idempotent-Replay")
                        .path("schema")
                        .path("type")
                        .asText())
                .isEqualTo("string");
    }

    @Test
    void documentsPaymentInstructionRequestAndResponseSchemas() throws Exception {
        var response = send("GET", "/v3/api-docs");

        assertThat(response.statusCode()).isEqualTo(200);
        var schemas = read(response.body()).path("components").path("schemas");
        var request = schemas.path("CreatePaymentInstructionRequest");
        var failureRequest = schemas.path("FailPaymentInstructionRequest");
        var resource = schemas.path("PaymentInstructionResponse");

        assertThat(request.path("description").asText()).isEqualTo("Request to create a payment instruction.");
        assertThat(request.path("properties")
                        .path("idempotencyKey")
                        .path("example")
                        .asText())
                .isEqualTo("payment-20260831-0001");
        var requiredProperties = java.util.stream.StreamSupport.stream(
                        request.path("required").spliterator(), false)
                .map(JsonNode::asText)
                .toList();
        assertThat(requiredProperties)
                .containsExactlyInAnyOrder("amount", "correlationId", "currency", "idempotencyKey");
        assertThat(request.path("properties").path("currency").path("pattern").asText())
                .isEqualTo("[A-Za-z]{3}");
        assertThat(failureRequest.path("description").asText())
                .isEqualTo("Request to record why a payment instruction failed.");
        assertThat(failureRequest
                        .path("properties")
                        .path("reason")
                        .path("example")
                        .asText())
                .isEqualTo("Provider rejected payment.");
        assertThat(resource.path("description").asText())
                .isEqualTo("Payment instruction resource returned by the internal payment API.");
        assertThat(resource.path("properties")
                        .path("instructionId")
                        .path("format")
                        .asText())
                .isEqualTo("uuid");
        var statuses = java.util.stream.StreamSupport.stream(
                        resource.path("properties").path("status").path("enum").spliterator(), false)
                .map(JsonNode::asText)
                .toList();
        assertThat(statuses).containsExactlyInAnyOrder("PENDING", "COMPLETED", "FAILED");
    }

    private String createInstruction(String idempotencyKey) throws Exception {
        var response = sendJson("POST", "/internal/v1/payment-instructions", """
                {
                  "idempotencyKey": "%s",
                  "correlationId": "%s",
                  "amount": 25.00,
                  "currency": "USD",
                  "description": "Lifecycle setup"
                }
                """.formatted(
                        idempotencyKey + "-" + UUID.randomUUID(), "correlation-" + UUID.randomUUID()));

        assertThat(response.statusCode()).isEqualTo(201);
        return read(response.body()).path("instructionId").asText();
    }

    private static void assertResponseContract(
            com.fasterxml.jackson.databind.JsonNode operation, Set<String> expectedResponseCodes) {
        var responses = operation.path("responses");
        assertThat(responses.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(expectedResponseCodes);
        expectedResponseCodes.stream().filter(code -> !code.startsWith("2")).forEach(code -> assertThat(
                        responses.path(code).path("content").has("application/problem+json"))
                .as("response %s should document Problem Details", code)
                .isTrue());
        expectedResponseCodes.stream().filter(code -> code.startsWith("2")).forEach(code -> assertThat(
                        responses.path(code).path("content").has("application/json"))
                .as("response %s should document JSON", code)
                .isTrue());
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + TestSecurityConfig.TEST_BEARER_TOKEN)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendJson(String method, String path, String body) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .method(method, HttpRequest.BodyPublishers.ofString(body))
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + TestSecurityConfig.TEST_BEARER_TOKEN)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendJsonWithoutAuthorization(String method, String path, String body)
            throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .method(method, HttpRequest.BodyPublishers.ofString(body))
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendJsonWithAuthorization(String method, String path, String body, String token)
            throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .method(method, HttpRequest.BodyPublishers.ofString(body))
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(java.util.concurrent.Future<HttpResponse<String>> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private void assertAuthenticationProblem(HttpResponse<String> response, String expectedInstance) throws Exception {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("www-authenticate")).hasValue("Bearer");
        assertContentType(response, "application/problem+json");
        var problem = read(response.body());
        assertThat(problem.path("type").asText()).isEqualTo("urn:digital-bank:payment:authentication-required");
        assertThat(problem.path("title").asText()).isEqualTo("Payment authentication required");
        assertThat(problem.path("instance").asText()).isEqualTo(expectedInstance);
        assertThat(problem.path("detail").asText()).doesNotContain("issuer").doesNotContain("token");
    }

    private com.fasterxml.jackson.databind.JsonNode read(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception exception) {
            throw new AssertionError("Expected a JSON response", exception);
        }
    }

    private static void assertContentType(HttpResponse<String> response, String expectedPrefix) {
        assertThat(response.headers().firstValue("content-type"))
                .hasValueSatisfying(contentType -> assertThat(contentType).startsWith(expectedPrefix));
    }
}
