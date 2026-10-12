package com.paycore.payments;

/** A saved payment and what the processor said when we tried to charge the card. */
public record ChargeAttempt(Payment payment, ProcessorClient.ChargeResult result) {
}
