package com.paycore.payments;

/**
 * Pay {@code amount} minor units to a merchant's wallet with a fake card token such as "tok_visa".
 * {@code customer} is an optional label for who paid, used to list their payments.
 * {@code captureMethod} is "automatic" (the default when left out) or "manual".
 */
public record CreatePaymentRequest(long merchantWalletId, long amount, String currency, String cardToken,
                                   String customer, String captureMethod) {
}
