package com.digitalbank.paymentservice.application.port.in;

public interface CreatePaymentInstructionInputPort {

    PaymentInstructionResult register(CreatePaymentInstructionCommand command);
}
