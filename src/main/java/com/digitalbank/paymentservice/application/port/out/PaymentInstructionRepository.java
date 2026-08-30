package com.digitalbank.paymentservice.application.port.out;

import com.digitalbank.paymentservice.domain.model.PaymentInstruction;
import com.digitalbank.paymentservice.domain.model.PaymentInstructionId;
import java.util.Optional;

public interface PaymentInstructionRepository {

    Optional<PaymentInstruction> findById(PaymentInstructionId instructionId);

    PaymentInstructionSaveResult saveIfAbsent(PaymentInstruction instruction);

    PaymentInstruction save(PaymentInstruction instruction);
}
