package com.paycore.transfers;

/** {@code id} is the journal entry that recorded the transfer. */
public record TransferResponse(long id, long fromWalletId, long toWalletId, long amount, String currency, String note) {
}
