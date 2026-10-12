package com.paycore.payments;

public record PaymentResponse(long id, long merchantWalletId, long amount, String currency, String status, long fee) {

    static PaymentResponse of(Payment payment) {
        return new PaymentResponse(payment.id(), payment.merchantWalletId(), payment.amount().amount(),
                payment.amount().currency(), payment.status().apiName(), payment.fee().amount());
    }
}
