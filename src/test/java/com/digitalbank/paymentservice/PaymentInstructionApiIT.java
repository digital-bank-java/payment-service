package com.digitalbank.paymentservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentInstructionApiIT {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @LocalServerPort
    private int port;

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
    void malformedInstructionIdAndFailureContractStayWithinBoundaryRules() throws Exception {
        var malformedId = send("POST", "/internal/v1/payment-instructions/not-a-uuid/completion");

        assertThat(malformedId.statusCode()).isEqualTo(400);
        assertContentType(malformedId, "application/problem+json");
        var malformedIdProblem = read(malformedId.body());
        assertThat(malformedIdProblem.path("type").asText())
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
        assertThat(document.path("paths")
                        .path("/internal/v1/payment-instructions")
                        .path("post")
                        .path("responses")
                        .has("409"))
                .isTrue();
        assertThat(document.path("paths")
                        .path("/internal/v1/payment-instructions/{instructionId}/failure")
                        .path("post")
                        .path("responses")
                        .has("400"))
                .isTrue();
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

    private HttpResponse<String> send(String method, String path) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .header("Accept", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendJson(String method, String path, String body) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .method(method, HttpRequest.BodyPublishers.ofString(body))
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
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
