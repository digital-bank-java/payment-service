package com.digitalbank.paymentservice.application.port.in;

import com.digitalbank.paymentservice.domain.model.PaymentInstructionId;

public interface GetPaymentInstructionInputPort {

    PaymentInstructionResult get(PaymentInstructionId instructionId);
}
