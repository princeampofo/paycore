package com.paycore.payments;

import java.time.Instant;

public record PaymentResponse(long id, long merchantWalletId, long amount, String currency, String status, long fee,
                              String customer, String captureMethod, Instant createdAt) {

    static PaymentResponse of(Payment payment) {
        return new PaymentResponse(payment.id(), payment.merchantWalletId(), payment.amount().amount(),
                payment.amount().currency(), payment.status().apiName(), payment.fee().amount(),
                payment.customer(), payment.captureMethod().apiName(), payment.createdAt());
    }
}
