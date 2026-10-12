package com.paycore.payments;

import com.paycore.ledger.Money;

public record Payment(long id, long merchantWalletId, Money amount, String cardToken, PaymentStatus status,
                      String processorRef, Money fee, long version) {

    /** This payment after a successful status update (which also bumps the version). */
    Payment moved(PaymentStatus next, Money newFee) {
        return new Payment(id, merchantWalletId, amount, cardToken, next, processorRef, newFee, version + 1);
    }
}
