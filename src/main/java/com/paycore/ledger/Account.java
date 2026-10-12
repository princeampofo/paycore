package com.paycore.ledger;

public record Account(long id, AccountType type, String currency, String owner) {
}
