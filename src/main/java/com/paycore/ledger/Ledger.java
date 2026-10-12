package com.paycore.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Writes balanced journal entries and keeps the cached balances in sync.
 *
 * {@link #post} runs in one transaction (or joins the caller's), so the entry, its postings
 * and the balance updates are either all written or none are.
 */
@Repository
public class Ledger {

    private static final RowMapper<Account> ACCOUNT = (rs, row) -> new Account(
            rs.getLong("id"), AccountType.valueOf(rs.getString("type")), rs.getString("currency"), rs.getString("owner"));

    private final JdbcTemplate jdbc;

    public Ledger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Account openWallet(String currency, String owner) {
        Money.of(0, currency); // validates the currency code
        long id = jdbc.queryForObject(
                "INSERT INTO accounts (type, currency, owner) VALUES ('WALLET', ?, ?) RETURNING id",
                Long.class, currency, owner);
        jdbc.update("INSERT INTO balances (account_id, allow_negative) VALUES (?, false)", id);
        return new Account(id, AccountType.WALLET, currency, owner);
    }

    public Optional<Account> findAccount(long id) {
        return jdbc.query("SELECT * FROM accounts WHERE id = ?", ACCOUNT, id).stream().findFirst();
    }

    /** A customer's or merchant's wallet. System accounts are never returned, so callers can't move their money. */
    public Optional<Account> findWallet(long id) {
        return findAccount(id).filter(account -> account.type() == AccountType.WALLET);
    }

    /** The single holds / fee_revenue / external_funding account for a currency (created by the migration). */
    public Account systemAccount(AccountType type, String currency) {
        return jdbc.queryForObject("SELECT * FROM accounts WHERE type = ? AND currency = ?",
                ACCOUNT, type.name(), currency);
    }

    public Money balance(long accountId) {
        return jdbc.queryForObject("""
                SELECT b.balance, a.currency FROM balances b JOIN accounts a ON a.id = b.account_id
                WHERE b.account_id = ?""",
                (rs, row) -> Money.of(rs.getLong("balance"), rs.getString("currency")), accountId);
    }

    /**
     * A wallet's history, newest first: up to {@code limit} journal entries that touched it, older
     * than entry {@code beforeId} (pass Long.MAX_VALUE for the newest). Each comes with all of its
     * postings, so the caller can see who was on the other side.
     */
    public List<WalletEntry> entriesFor(long walletId, long beforeId, int limit) {
        Map<Long, WalletEntry> entries = new LinkedHashMap<>();
        jdbc.query("""
                SELECT e.id, e.description, e.note, e.created_at, p.account_id, p.amount
                FROM journal_entries e JOIN postings p ON p.entry_id = e.id
                WHERE e.id IN (SELECT entry_id FROM postings
                               WHERE account_id = ? AND entry_id < ?
                               ORDER BY entry_id DESC LIMIT ?)
                ORDER BY e.id DESC, p.id""",
                rs -> {
                    long entryId = rs.getLong("id");
                    WalletEntry entry = entries.get(entryId);
                    if (entry == null) {
                        entry = new WalletEntry(entryId, rs.getString("description"), rs.getString("note"),
                                rs.getTimestamp("created_at").toInstant(), 0, new ArrayList<>());
                        entries.put(entryId, entry);
                    }
                    entry.postings().add(new WalletEntry.Line(rs.getLong("account_id"), rs.getLong("amount")));
                },
                walletId, beforeId, limit);
        return entries.values().stream().map(entry -> entry.forWallet(walletId)).toList();
    }

    /** Posts the entry and returns its journal entry id. */
    @Transactional
    public long post(JournalEntry entry) {
        Map<Long, Money> changes = netChangePerAccount(entry);
        Map<Long, LockedBalance> locked = lockBalancesInOrder(changes);

        Map<Long, Long> newBalances = new TreeMap<>();
        changes.forEach((accountId, change) -> {
            LockedBalance current = locked.get(accountId);
            long newBalance = Math.addExact(current.balance(), change.amount());
            // Checked while holding the row lock, so no other transaction can spend the same money
            // between this check and our update. The CHECK constraint backs this up.
            if (newBalance < 0 && !current.allowNegative()) {
                throw new InsufficientFundsException(accountId);
            }
            newBalances.put(accountId, newBalance);
        });

        long entryId = jdbc.queryForObject(
                "INSERT INTO journal_entries (description, note) VALUES (?, ?) RETURNING id",
                Long.class, entry.description(), entry.note());
        for (Posting posting : entry.postings()) {
            jdbc.update("INSERT INTO postings (entry_id, account_id, amount) VALUES (?, ?, ?)",
                    entryId, posting.accountId(), posting.amount().amount());
        }
        newBalances.forEach((accountId, balance) ->
                jdbc.update("UPDATE balances SET balance = ? WHERE account_id = ?", balance, accountId));
        return entryId;
    }

    /** One net change per account, sorted by account id (a TreeMap keeps its keys in order). */
    private static Map<Long, Money> netChangePerAccount(JournalEntry entry) {
        Map<Long, Money> changes = new TreeMap<>();
        for (Posting posting : entry.postings()) {
            changes.merge(posting.accountId(), posting.amount(), Money::plus);
        }
        return changes;
    }

    private record LockedBalance(long balance, boolean allowNegative) {
    }

    /**
     * Locks each balance row with SELECT ... FOR UPDATE, always in ascending account id order.
     *
     * Why the order matters: if transfer X (A→B) locked A then B while transfer Y (B→A) locked
     * B then A, each could end up holding one lock and waiting forever for the other (a deadlock).
     * When everyone locks the lower id first, both go for A first; one waits briefly, nobody is stuck.
     */
    private Map<Long, LockedBalance> lockBalancesInOrder(Map<Long, Money> changes) {
        Map<Long, LockedBalance> locked = new TreeMap<>();
        changes.forEach((accountId, change) -> {
            var rows = jdbc.query("""
                    SELECT b.balance, b.allow_negative, a.currency
                    FROM balances b JOIN accounts a ON a.id = b.account_id
                    WHERE b.account_id = ?
                    FOR UPDATE OF b""",
                    (rs, row) -> {
                        if (!rs.getString("currency").equals(change.currency())) {
                            throw new IllegalArgumentException("Account " + accountId + " holds "
                                    + rs.getString("currency") + ", cannot post " + change.currency());
                        }
                        return new LockedBalance(rs.getLong("balance"), rs.getBoolean("allow_negative"));
                    },
                    accountId);
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("Unknown account " + accountId);
            }
            locked.put(accountId, rows.getFirst());
        });
        return locked;
    }
}
