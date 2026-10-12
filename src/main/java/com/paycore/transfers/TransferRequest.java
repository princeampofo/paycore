package com.paycore.transfers;

/** Move {@code amount} minor units (1050 = 10.50 AED) from one wallet to another. */
public record TransferRequest(long fromWalletId, long toWalletId, long amount, String currency) {
}
