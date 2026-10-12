package com.paycore.api;

import com.paycore.ledger.Ledger;
import com.paycore.ledger.Money;
import com.paycore.ledger.WalletEntry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class WalletController {

    public record BalanceResponse(long walletId, long amount, String currency) {
    }

    private final Ledger ledger;

    public WalletController(Ledger ledger) {
        this.ledger = ledger;
    }

    /** {@code next_before} is the cursor for the next (older) page, or null on the last page. */
    public record EntriesResponse(List<WalletEntry> data, Long nextBefore) {
    }

    @GetMapping("/v1/wallets/{id}/balance")
    public BalanceResponse balance(@PathVariable long id) {
        requireWallet(id);
        Money balance = ledger.balance(id);
        return new BalanceResponse(id, balance.amount(), balance.currency());
    }

    /**
     * The wallet's ledger history, newest first. Pages by entry id rather than offset: new entries
     * arriving while you page don't shift what's on the next page.
     */
    @GetMapping("/v1/wallets/{id}/entries")
    public EntriesResponse entries(@PathVariable long id,
                                   @RequestParam(defaultValue = "20") int limit,
                                   @RequestParam(required = false) Long before) {
        requireWallet(id);
        if (limit < 1 || limit > 100) {
            throw ApiException.badRequest("invalid_limit", "limit must be between 1 and 100");
        }
        // Fetch one extra to know whether there's another page.
        List<WalletEntry> page = ledger.entriesFor(id, before == null ? Long.MAX_VALUE : before, limit + 1);
        if (page.size() <= limit) {
            return new EntriesResponse(page, null);
        }
        List<WalletEntry> data = page.subList(0, limit);
        return new EntriesResponse(data, data.getLast().id());
    }

    private void requireWallet(long id) {
        ledger.findWallet(id)
                .orElseThrow(() -> ApiException.notFound("wallet_not_found", "No wallet with id " + id));
    }
}
