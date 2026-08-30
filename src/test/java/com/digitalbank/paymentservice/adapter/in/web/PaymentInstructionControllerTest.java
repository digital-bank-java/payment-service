package com.digitalbank.paymentservice.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.digitalbank.paymentservice.application.port.in.CompletePaymentInstructionInputPort;
import com.digitalbank.paymentservice.application.port.in.CreatePaymentInstructionCommand;
import com.digitalbank.paymentservice.application.port.in.CreatePaymentInstructionInputPort;
import com.digitalbank.paymentservice.application.port.in.FailPaymentInstructionInputPort;
import com.digitalbank.paymentservice.application.port.in.PaymentInstructionResult;
import com.digitalbank.paymentservice.domain.exception.PaymentInstructionIdempotencyConflictException;
import com.digitalbank.paymentservice.domain.exception.PaymentInstructionNotFoundException;
import com.digitalbank.paymentservice.domain.model.PaymentInstructionId;
import com.digitalbank.paymentservice.domain.model.PaymentInstructionStatus;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PaymentInstructionControllerTest {

    @Test
    void createEndpointMapsRequestBodyToInputPortAndLocationHeader() throws Exception {
        var captured = new AtomicReference<CreatePaymentInstructionCommand>();
        var response = new PaymentInstructionResult(
                new PaymentInstructionId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                "correlation-http-001",
                new BigDecimal("125.50"),
                "AED",
                PaymentInstructionStatus.PENDING,
                null,
                false);
        CreatePaymentInstructionInputPort createPort = command -> {
            captured.set(command);
            return response;
        };
        var mockMvc = mockMvc(
                createPort,
                instructionId -> {
                    throw new UnsupportedOperationException();
                },
                (instructionId, reason) -> {
                    throw new UnsupportedOperationException();
                });

        mockMvc.perform(post("/internal/v1/payment-instructions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "idempotencyKey": "payment-http-001",
                                  "correlationId": "correlation-http-001",
                                  "amount": 125.50,
                                  "currency": "aed",
                                  "description": "Utility bill payment"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                                "Location", "/internal/v1/payment-instructions/11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.instructionId").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.currency").value("AED"))
                .andExpect(jsonPath("$.idempotentReplay").value(false));

        org.assertj.core.api.Assertions.assertThat(captured.get())
                .isEqualTo(new CreatePaymentInstructionCommand(
                        "payment-http-001",
                        "correlation-http-001",
                        new BigDecimal("125.50"),
                        "aed",
                        "Utility bill payment"));
    }

    @Test
    void invalidRequestAndDomainFailuresUseProblemDetails() throws Exception {
        var missingId = new PaymentInstructionId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        CompletePaymentInstructionInputPort completePort = instructionId -> {
            throw new PaymentInstructionNotFoundException(missingId);
        };
        FailPaymentInstructionInputPort failPort = (instructionId, reason) -> {
            throw new PaymentInstructionIdempotencyConflictException("payment-http-001");
        };
        var mockMvc = mockMvc(
                command -> {
                    throw new UnsupportedOperationException();
                },
                completePort,
                failPort);

        mockMvc.perform(post("/internal/v1/payment-instructions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "idempotencyKey": "",
                                  "correlationId": " ",
                                  "amount": 0,
                                  "currency": "dirham"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(
                        header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")))
                .andExpect(jsonPath("$.type").value("https://digital-bank-java.local/problems/validation-error"))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors").isArray());

        mockMvc.perform(post("/internal/v1/payment-instructions/" + missingId.value() + "/completion"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type")
                        .value("https://digital-bank-java.local/problems/payment-instruction-not-found"))
                .andExpect(jsonPath("$.instructionId").value(missingId.value().toString()));

        mockMvc.perform(post("/internal/v1/payment-instructions/" + missingId.value() + "/failure")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "provider rejected payment"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("https://digital-bank-java.local/problems/payment-instruction-idempotency-conflict"));
    }

    @Test
    void failureEndpointBindsReasonAndLifecycleConflict() throws Exception {
        var capturedInstructionId = new AtomicReference<PaymentInstructionId>();
        var capturedReason = new AtomicReference<String>();
        var response = new PaymentInstructionResult(
                new PaymentInstructionId(UUID.fromString("33333333-3333-3333-3333-333333333333")),
                "correlation-http-003",
                new BigDecimal("25.00"),
                "USD",
                PaymentInstructionStatus.FAILED,
                "provider rejected payment",
                false);
        var mockMvc = mockMvc(
                command -> {
                    throw new UnsupportedOperationException();
                },
                instructionId -> {
                    throw new IllegalStateException("Payment instruction is already FAILED");
                },
                (instructionId, reason) -> {
                    capturedInstructionId.set(instructionId);
                    capturedReason.set(reason);
                    return response;
                });

        mockMvc.perform(post("/internal/v1/payment-instructions/33333333-3333-3333-3333-333333333333/failure")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "provider rejected payment"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("provider rejected payment"));

        org.assertj.core.api.Assertions.assertThat(capturedInstructionId.get())
                .isEqualTo(new PaymentInstructionId(UUID.fromString("33333333-3333-3333-3333-333333333333")));
        org.assertj.core.api.Assertions.assertThat(capturedReason.get()).isEqualTo("provider rejected payment");

        mockMvc.perform(post("/internal/v1/payment-instructions/33333333-3333-3333-3333-333333333333/completion"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("https://digital-bank-java.local/problems/payment-instruction-state-conflict"))
                .andExpect(jsonPath("$.title").value("Payment instruction state conflict"));
    }

    private static MockMvc mockMvc(
            CreatePaymentInstructionInputPort createPort,
            CompletePaymentInstructionInputPort completePort,
            FailPaymentInstructionInputPort failPort) {
        return MockMvcBuilders.standaloneSetup(new PaymentInstructionController(createPort, completePort, failPort))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }
}
