package com.digitalbank.paymentservice.application.port.in;

import com.digitalbank.paymentservice.domain.model.PaymentInstructionId;

public interface CompletePaymentInstructionInputPort {

    PaymentInstructionResult complete(PaymentInstructionId instructionId);
}
