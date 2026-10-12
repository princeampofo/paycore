package com.paycore;

import com.paycore.ledger.AccountType;
import com.paycore.ledger.JournalEntry;
import com.paycore.ledger.Ledger;
import com.paycore.ledger.Money;
import com.paycore.ledger.Posting;

/** Opens AED wallets with a starting balance, funded from external_funding like a card top-up would be. */
public final class TestWallets {

    private TestWallets() {
    }

    public static long fundedWallet(Ledger ledger, String owner, long minorUnits) {
        long wallet = ledger.openWallet("AED", owner).id();
        if (minorUnits > 0) {
            long funding = ledger.systemAccount(AccountType.EXTERNAL_FUNDING, "AED").id();
            Money amount = Money.of(minorUnits, "AED");
            ledger.post(JournalEntry.of("top-up",
                    new Posting(funding, amount.negate()),
                    new Posting(wallet, amount)));
        }
        return wallet;
    }
}
