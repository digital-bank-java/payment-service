package com.digitalbank.paymentservice.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

record FailPaymentInstructionRequest(
        @NotBlank(message = "must not be blank") String reason) {}
