package com.paycore.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks the ledger invariants straight from the database. An empty list means healthy.
 *
 * 1. Every journal entry's postings sum to zero.
 * 2. Every cached balance equals the sum of that account's postings.
 * 3. No account that may not go negative (a wallet) is negative.
 */
@Component
public class LedgerInvariants {

    private final JdbcTemplate jdbc;

    public LedgerInvariants(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // REPEATABLE READ runs all checks against one snapshot, so a transfer committing halfway
    // through can't make a healthy ledger look broken.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<String> violations() {
        List<String> violations = new ArrayList<>();

        violations.addAll(jdbc.queryForList("""
                SELECT 'entry ' || entry_id || ' is off by ' || SUM(amount)
                FROM postings
                GROUP BY entry_id
                HAVING SUM(amount) <> 0""", String.class));

        violations.addAll(jdbc.queryForList("""
                SELECT 'account ' || b.account_id || ' has cached balance ' || b.balance
                       || ' but its postings sum to ' || COALESCE(p.total, 0)
                FROM balances b
                LEFT JOIN (SELECT account_id, SUM(amount) AS total FROM postings GROUP BY account_id) p
                       ON p.account_id = b.account_id
                WHERE b.balance <> COALESCE(p.total, 0)""", String.class));

        violations.addAll(jdbc.queryForList("""
                SELECT 'account ' || account_id || ' may not go negative but has balance ' || balance
                FROM balances
                WHERE NOT allow_negative AND balance < 0""", String.class));

        return violations;
    }
}
