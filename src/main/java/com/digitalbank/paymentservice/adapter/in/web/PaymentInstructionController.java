package com.digitalbank.paymentservice.adapter.in.web;

import com.digitalbank.paymentservice.application.port.in.CompletePaymentInstructionInputPort;
import com.digitalbank.paymentservice.application.port.in.CreatePaymentInstructionInputPort;
import com.digitalbank.paymentservice.application.port.in.FailPaymentInstructionInputPort;
import com.digitalbank.paymentservice.domain.model.PaymentInstructionId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Payment Instructions")
@Validated
class PaymentInstructionController {

    private static final String VALIDATION_PROBLEM_EXAMPLE = """
            {
              "type": "https://digital-bank-java.local/problems/validation-error",
              "title": "Invalid request",
              "status": 400,
              "detail": "Request validation failed",
              "errors": [
                {
                  "field": "currency",
                  "message": "must be a three-letter currency code"
                }
              ]
            }
            """;

    private static final String NOT_FOUND_PROBLEM_EXAMPLE = """
            {
              "type": "https://digital-bank-java.local/problems/payment-instruction-not-found",
              "title": "Payment instruction not found",
              "status": 404,
              "detail": "Payment instruction was not found: 3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "instructionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
            }
            """;

    private static final String IDEMPOTENCY_CONFLICT_PROBLEM_EXAMPLE = """
            {
              "type": "https://digital-bank-java.local/problems/payment-instruction-idempotency-conflict",
              "title": "Payment instruction idempotency conflict",
              "status": 409,
              "detail": "Idempotency key was already used with a different payment request: payment-http-001",
              "idempotencyKey": "payment-http-001"
            }
            """;

    private static final String STATE_CONFLICT_PROBLEM_EXAMPLE = """
            {
              "type": "https://digital-bank-java.local/problems/payment-instruction-state-conflict",
              "title": "Payment instruction state conflict",
              "status": 409,
              "detail": "Payment instruction is already COMPLETED"
            }
            """;

    private final CreatePaymentInstructionInputPort createPaymentInstructionInputPort;
    private final CompletePaymentInstructionInputPort completePaymentInstructionInputPort;
    private final FailPaymentInstructionInputPort failPaymentInstructionInputPort;

    PaymentInstructionController(
            CreatePaymentInstructionInputPort createPaymentInstructionInputPort,
            CompletePaymentInstructionInputPort completePaymentInstructionInputPort,
            FailPaymentInstructionInputPort failPaymentInstructionInputPort) {
        this.createPaymentInstructionInputPort = createPaymentInstructionInputPort;
        this.completePaymentInstructionInputPort = completePaymentInstructionInputPort;
        this.failPaymentInstructionInputPort = failPaymentInstructionInputPort;
    }

    @PostMapping("/internal/v1/payment-instructions")
    @Operation(summary = "Create a payment instruction")
    @ApiResponse(
            responseCode = "201",
            description = "Payment instruction created",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentInstructionResponse.class)))
    @ApiResponse(
            responseCode = "200",
            description = "Idempotent replay of an existing payment instruction",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentInstructionResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples =
                                    @ExampleObject(
                                            name = "validation-error",
                                            summary = "Validation failure",
                                            value = VALIDATION_PROBLEM_EXAMPLE)))
    @ApiResponse(
            responseCode = "409",
            description = "Idempotency key conflicts with an existing payment instruction",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples =
                                    @ExampleObject(
                                            name = "payment-instruction-idempotency-conflict",
                                            summary = "Conflicting payment instruction request",
                                            value = IDEMPOTENCY_CONFLICT_PROBLEM_EXAMPLE)))
    ResponseEntity<PaymentInstructionResponse> createPaymentInstruction(
            @Valid @RequestBody CreatePaymentInstructionRequest request) {
        var result = createPaymentInstructionInputPort.register(request.toCommand());
        var response = PaymentInstructionResponse.from(result);
        var status = result.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED;
        var builder = ResponseEntity.status(status);
        if (result.idempotentReplay()) {
            builder.header("Idempotent-Replay", "true");
        }
        return builder.location(URI.create("/internal/v1/payment-instructions/" + response.instructionId()))
                .body(response);
    }

    @PostMapping("/internal/v1/payment-instructions/{instructionId}/completion")
    @Operation(summary = "Complete a payment instruction")
    @ApiResponse(
            responseCode = "200",
            description = "Payment instruction completed",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentInstructionResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Payment instruction not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples =
                                    @ExampleObject(
                                            name = "payment-instruction-not-found",
                                            summary = "Unknown payment instruction",
                                            value = NOT_FOUND_PROBLEM_EXAMPLE)))
    @ApiResponse(
            responseCode = "409",
            description = "Payment instruction is already in a different terminal state",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples =
                                    @ExampleObject(
                                            name = "payment-instruction-state-conflict",
                                            summary = "Lifecycle state conflict",
                                            value = STATE_CONFLICT_PROBLEM_EXAMPLE)))
    ResponseEntity<PaymentInstructionResponse> completePaymentInstruction(@PathVariable UUID instructionId) {
        var result = completePaymentInstructionInputPort.complete(new PaymentInstructionId(instructionId));
        return ResponseEntity.ok(PaymentInstructionResponse.from(result));
    }

    @PostMapping("/internal/v1/payment-instructions/{instructionId}/failure")
    @Operation(summary = "Fail a payment instruction")
    @ApiResponse(
            responseCode = "200",
            description = "Payment instruction failed",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentInstructionResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples =
                                    @ExampleObject(
                                            name = "validation-error",
                                            summary = "Validation failure",
                                            value = VALIDATION_PROBLEM_EXAMPLE)))
    @ApiResponse(
            responseCode = "404",
            description = "Payment instruction not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples =
                                    @ExampleObject(
                                            name = "payment-instruction-not-found",
                                            summary = "Unknown payment instruction",
                                            value = NOT_FOUND_PROBLEM_EXAMPLE)))
    @ApiResponse(
            responseCode = "409",
            description = "Payment instruction lifecycle conflict",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples =
                                    @ExampleObject(
                                            name = "payment-instruction-state-conflict",
                                            summary = "Lifecycle state conflict",
                                            value = STATE_CONFLICT_PROBLEM_EXAMPLE)))
    ResponseEntity<PaymentInstructionResponse> failPaymentInstruction(
            @PathVariable UUID instructionId, @Valid @RequestBody FailPaymentInstructionRequest request) {
        var result = failPaymentInstructionInputPort.fail(new PaymentInstructionId(instructionId), request.reason());
        return ResponseEntity.ok(PaymentInstructionResponse.from(result));
    }
}
