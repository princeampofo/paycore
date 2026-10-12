package com.paycore.api;

import com.paycore.ledger.Ledger;
import com.paycore.ledger.Money;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WalletController {

    public record BalanceResponse(long walletId, long amount, String currency) {
    }

    private final Ledger ledger;

    public WalletController(Ledger ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/v1/wallets/{id}/balance")
    public BalanceResponse balance(@PathVariable long id) {
        ledger.findWallet(id)
                .orElseThrow(() -> ApiException.notFound("wallet_not_found", "No wallet with id " + id));
        Money balance = ledger.balance(id);
        return new BalanceResponse(id, balance.amount(), balance.currency());
    }
}
