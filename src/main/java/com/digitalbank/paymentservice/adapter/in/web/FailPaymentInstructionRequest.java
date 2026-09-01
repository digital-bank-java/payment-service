package com.digitalbank.paymentservice.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "FailPaymentInstructionRequest", description = "Request to record why a payment instruction failed.")
record FailPaymentInstructionRequest(
        @Schema(description = "Reason recorded for the failed instruction.", example = "Provider rejected payment.")
        @NotBlank(message = "must not be blank")
        String reason) {}
