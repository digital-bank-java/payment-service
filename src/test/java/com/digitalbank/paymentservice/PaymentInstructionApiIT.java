package com.digitalbank.paymentservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentInstructionApiIT {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

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

        var created = sendJson("POST", "/internal/v1/payment-instructions", requestBody, status().isCreated());
        var replay = sendJson("POST", "/internal/v1/payment-instructions", requestBody, status().isOk());

        assertContentType(created, "application/json");
        assertThat(created.getResponse().getHeader("Location")).contains("/internal/v1/payment-instructions/");
        var createdBody = read(created.getResponse().getContentAsString());
        assertThat(createdBody.path("instructionId").asText()).isNotBlank();
        assertThat(createdBody.path("status").asText()).isEqualTo("PENDING");
        assertThat(createdBody.path("currency").asText()).isEqualTo("AED");
        assertThat(createdBody.path("idempotentReplay").asBoolean()).isFalse();

        assertThat(replay.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        var replayBody = read(replay.getResponse().getContentAsString());
        assertThat(replayBody.path("instructionId").asText())
                .isEqualTo(createdBody.path("instructionId").asText());
        assertThat(replayBody.path("idempotentReplay").asBoolean()).isTrue();
    }

    @Test
    void completesAndFailsPaymentInstructions() throws Exception {
        var completedInstructionId = createInstruction("payment-http-complete");
        var completion = send(
                "POST", "/internal/v1/payment-instructions/" + completedInstructionId + "/completion", status().isOk());

        assertContentType(completion, "application/json");
        assertThat(read(completion.getResponse().getContentAsString())
                        .path("status")
                        .asText())
                .isEqualTo("COMPLETED");

        var failedInstructionId = createInstruction("payment-http-fail");
        var failure = sendJson(
                "POST", "/internal/v1/payment-instructions/" + failedInstructionId + "/failure", """
                {
                  "reason": "provider rejected payment"
                }
                """, status().isOk());

        assertContentType(failure, "application/json");
        var failed = read(failure.getResponse().getContentAsString());
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
                """, status().isBadRequest());

        assertContentType(invalidCreate, "application/problem+json");
        var invalidCreateProblem = read(invalidCreate.getResponse().getContentAsString());
        assertThat(invalidCreateProblem.path("type").asText())
                .isEqualTo("https://digital-bank-java.local/problems/validation-error");
        assertThat(invalidCreateProblem.path("title").asText()).isEqualTo("Invalid request");
        assertThat(invalidCreateProblem.path("errors")).isNotEmpty();

        var instructionId = createInstruction("payment-http-conflict");
        send("POST", "/internal/v1/payment-instructions/" + instructionId + "/completion", status().isOk());
        var conflict = sendJson(
                "POST", "/internal/v1/payment-instructions/" + instructionId + "/failure", """
                {
                  "reason": "late provider error"
                }
                """, status().isConflict());

        assertContentType(conflict, "application/problem+json");
        var conflictProblem = read(conflict.getResponse().getContentAsString());
        assertThat(conflictProblem.path("type").asText())
                .isEqualTo("https://digital-bank-java.local/problems/payment-instruction-state-conflict");
        assertThat(conflictProblem.path("title").asText()).isEqualTo("Payment instruction state conflict");

        var missing = send(
                "POST",
                "/internal/v1/payment-instructions/" + UUID.randomUUID() + "/completion",
                status().isNotFound());

        assertContentType(missing, "application/problem+json");
        var missingProblem = read(missing.getResponse().getContentAsString());
        assertThat(missingProblem.path("type").asText())
                .isEqualTo("https://digital-bank-java.local/problems/payment-instruction-not-found");
    }

    @Test
    void publishesPaymentInstructionContractInOpenApiDocument() throws Exception {
        var response = mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        var document = read(response.getResponse().getContentAsString());
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
        var response = sendJson(
                "POST",
                "/internal/v1/payment-instructions",
                """
                {
                  "idempotencyKey": "%s",
                  "correlationId": "%s",
                  "amount": 25.00,
                  "currency": "USD",
                  "description": "Lifecycle setup"
                }
                """.formatted(idempotencyKey + "-" + UUID.randomUUID(), "correlation-" + UUID.randomUUID()),
                status().isCreated());

        return read(response.getResponse().getContentAsString())
                .path("instructionId")
                .asText();
    }

    private MvcResult send(String method, String path, ResultMatcher expectedStatus) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                                HttpMethod.valueOf(method), path)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(expectedStatus)
                .andReturn();
    }

    private MvcResult sendJson(String method, String path, String body, ResultMatcher expectedStatus) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                                HttpMethod.valueOf(method), path)
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(expectedStatus)
                .andReturn();
    }

    private com.fasterxml.jackson.databind.JsonNode read(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception exception) {
            throw new AssertionError("Expected a JSON response", exception);
        }
    }

    private static void assertContentType(MvcResult response, String expectedPrefix) {
        assertThat(response.getResponse().getContentType()).startsWith(expectedPrefix);
    }
}
