package com.paycore.ledger;

import java.util.Objects;

/** One line of a journal entry: a signed amount on one account (positive adds, negative takes). */
public record Posting(long accountId, Money amount) {

    public Posting {
        Objects.requireNonNull(amount, "amount");
        if (amount.isZero()) {
            throw new IllegalArgumentException("A posting must move a non-zero amount");
        }
    }
}
