package com.digitalbank.paymentservice.adapter.in.web;

import com.digitalbank.paymentservice.domain.exception.PaymentInstructionIdempotencyConflictException;
import com.digitalbank.paymentservice.domain.exception.PaymentInstructionNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(PaymentInstructionNotFoundException.class)
    ResponseEntity<ProblemDetail> handlePaymentInstructionNotFound(PaymentInstructionNotFoundException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Payment instruction not found");
        problem.setType(URI.create("https://digital-bank-java.local/problems/payment-instruction-not-found"));
        problem.setProperty("instructionId", exception.instructionId().value());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(PaymentInstructionIdempotencyConflictException.class)
    ResponseEntity<ProblemDetail> handleIdempotencyConflict(PaymentInstructionIdempotencyConflictException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Payment instruction idempotency conflict");
        problem.setType(
                URI.create("https://digital-bank-java.local/problems/payment-instruction-idempotency-conflict"));
        problem.setProperty("idempotencyKey", exception.idempotencyKey());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> handleLifecycleConflict(IllegalStateException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Payment instruction state conflict");
        problem.setType(URI.create("https://digital-bank-java.local/problems/payment-instruction-state-conflict"));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidationFailure(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest()
                .body(validationProblem(exception.getBindingResult().getFieldErrors().stream()
                        .map(error -> Map.of(
                                "field", error.getField(),
                                "message", String.valueOf(error.getDefaultMessage())))
                        .toList()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException exception) {
        return ResponseEntity.badRequest()
                .body(validationProblem(exception.getConstraintViolations().stream()
                        .map(violation -> Map.of(
                                "field", violation.getPropertyPath().toString(),
                                "message", violation.getMessage()))
                        .toList()));
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentTypeMismatchException.class,
        ResponseStatusException.class
    })
    ResponseEntity<ProblemDetail> handleBadRequest(Exception exception) {
        var detail = exception instanceof ResponseStatusException responseStatusException
                ? responseStatusException.getReason()
                : exception.getMessage();
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Invalid request");
        problem.setType(URI.create("https://digital-bank-java.local/problems/validation-error"));
        return ResponseEntity.badRequest().body(problem);
    }

    private static ProblemDetail validationProblem(Object errors) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setTitle("Invalid request");
        problem.setType(URI.create("https://digital-bank-java.local/problems/validation-error"));
        problem.setProperty("errors", errors);
        return problem;
    }
}
