package com.paycore.payments;

/** Pay {@code amount} minor units to a merchant's wallet with a fake card token such as "tok_visa". */
public record CreatePaymentRequest(long merchantWalletId, long amount, String currency, String cardToken) {
}
