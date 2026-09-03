package com.digitalbank.paymentservice.application.port.in;

import com.digitalbank.paymentservice.domain.model.PaymentInstructionId;

public interface FailPaymentInstructionInputPort {

    PaymentInstructionResult fail(PaymentInstructionId instructionId, String reason);
}
