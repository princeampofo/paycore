package com.paycore.transfers;

import com.paycore.api.ApiException;
import com.paycore.ledger.Account;
import com.paycore.ledger.AccountType;
import com.paycore.ledger.JournalEntry;
import com.paycore.ledger.Ledger;
import com.paycore.ledger.Money;
import com.paycore.ledger.Posting;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;

@Service
public class TransferService {

    private final Ledger ledger;

    public TransferService(Ledger ledger) {
        this.ledger = ledger;
    }

    /**
     * Moves money between two wallets as one journal entry. The ledger locks both balances,
     * so concurrent transfers out of the same wallet can never spend the same money twice.
     *
     * @throws com.paycore.ledger.InsufficientFundsException if the sender's wallet is too low
     */
    @Transactional
    public TransferResponse transferFunds(TransferRequest request) {
        if (request.amount() <= 0) {
            throw ApiException.badRequest("invalid_amount", "amount must be a positive number of minor units");
        }
        if (!isCurrencyCode(request.currency())) {
            throw ApiException.badRequest("invalid_currency", "currency must be an ISO 4217 code such as AED");
        }
        if (request.fromWalletId() == request.toWalletId()) {
            throw ApiException.badRequest("same_wallet", "Cannot transfer from a wallet to itself");
        }
        Account from = findWallet(request.fromWalletId());
        Account to = findWallet(request.toWalletId());
        if (!from.currency().equals(request.currency()) || !to.currency().equals(request.currency())) {
            throw ApiException.badRequest("currency_mismatch", "Both wallets must hold " + request.currency());
        }

        Money amount = Money.of(request.amount(), request.currency());
        long entryId = ledger.post(JournalEntry.of("transfer",
                new Posting(from.id(), amount.negate()),
                new Posting(to.id(), amount)));

        return new TransferResponse(entryId, from.id(), to.id(), amount.amount(), amount.currency());
    }

    // System accounts (holds, fee_revenue, external_funding) share the id space with wallets,
    // so we check the type: a client must never be able to move money out of them directly.
    private Account findWallet(long id) {
        return ledger.findAccount(id)
                .filter(account -> account.type() == AccountType.WALLET)
                .orElseThrow(() -> ApiException.notFound("wallet_not_found", "No wallet with id " + id));
    }

    private static boolean isCurrencyCode(String code) {
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }
}
