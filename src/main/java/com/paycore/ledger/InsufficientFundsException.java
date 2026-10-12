package com.paycore.ledger;

/** Posting the entry would take an account that may not go negative (a wallet) below zero. */
public class InsufficientFundsException extends RuntimeException {

    private final long accountId;

    public InsufficientFundsException(long accountId) {
        super("Account " + accountId + " has insufficient funds");
        this.accountId = accountId;
    }

    public long accountId() {
        return accountId;
    }
}
