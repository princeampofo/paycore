package com.paycore.ledger;

import java.time.Instant;
import java.util.List;

/**
 * One journal entry as seen from a wallet: {@code amount} is what it did to that wallet
 * (negative = money out), and {@code postings} shows every account involved.
 */
public record WalletEntry(long id, String description, String note, Instant createdAt, long amount,
                          List<Line> postings) {

    public record Line(long accountId, long amount) {
    }

    WalletEntry forWallet(long walletId) {
        long net = postings.stream().filter(line -> line.accountId() == walletId).mapToLong(Line::amount).sum();
        return new WalletEntry(id, description, note, createdAt, net, List.copyOf(postings));
    }
}
