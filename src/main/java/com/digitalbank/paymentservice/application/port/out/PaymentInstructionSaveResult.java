package com.digitalbank.paymentservice.application.port.out;

import com.digitalbank.paymentservice.domain.model.PaymentInstruction;

public record PaymentInstructionSaveResult(PaymentInstruction instruction, boolean inserted) {}
