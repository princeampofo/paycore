package com.paycore.ledger;

public enum AccountType {
    /** A customer's or merchant's money. Never negative. */
    WALLET,
    /** Money authorized on a card but not yet captured. */
    HOLDS,
    /** The platform's fee from each payment. */
    FEE_REVENUE,
    /**
     * The outside world (cards, banks). Money entering paycore is taken from here, so its
     * balance goes negative by exactly the amount that has come in.
     */
    EXTERNAL_FUNDING;

    public boolean allowsNegative() {
        return this == EXTERNAL_FUNDING;
    }
}
