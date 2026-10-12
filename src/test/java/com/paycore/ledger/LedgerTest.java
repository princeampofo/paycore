package com.paycore.ledger;

import com.paycore.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static com.paycore.TestWallets.fundedWallet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerTest {

    @Autowired
    Ledger ledger;
    @Autowired
    LedgerInvariants invariants;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void postingUpdatesCachedBalances() {
        long alice = fundedWallet(ledger, "alice", 10_000);
        long bob = fundedWallet(ledger, "bob", 0);

        ledger.post(JournalEntry.of("transfer",
                new Posting(alice, Money.of(-2_500, "AED")),
                new Posting(bob, Money.of(2_500, "AED"))));

        assertThat(ledger.balance(alice)).isEqualTo(Money.of(7_500, "AED"));
        assertThat(ledger.balance(bob)).isEqualTo(Money.of(2_500, "AED"));
        assertThat(invariants.violations()).isEmpty();
    }

    @Test
    void overdraftIsRejectedAndWritesNothing() {
        long alice = fundedWallet(ledger, "alice", 3_000);
        long bob = fundedWallet(ledger, "bob", 0);
        long entriesBefore = count("journal_entries");

        assertThatThrownBy(() -> ledger.post(JournalEntry.of("transfer",
                new Posting(alice, Money.of(-5_000, "AED")),
                new Posting(bob, Money.of(5_000, "AED")))))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(count("journal_entries")).isEqualTo(entriesBefore);
        assertThat(ledger.balance(alice)).isEqualTo(Money.of(3_000, "AED"));
        assertThat(ledger.balance(bob)).isEqualTo(Money.of(0, "AED"));
    }

    @Test
    void externalFundingIsTheOnlyAccountAllowedToGoNegative() {
        long before = ledger.balance(system(AccountType.EXTERNAL_FUNDING)).amount();

        fundedWallet(ledger, "alice", 1_000);

        assertThat(ledger.balance(system(AccountType.EXTERNAL_FUNDING)).amount()).isEqualTo(before - 1_000);
    }

    @Test
    void ledgerHistoryCannotBeEditedOrDeleted() {
        fundedWallet(ledger, "alice", 1_000);

        assertThatThrownBy(() -> jdbc.update("UPDATE postings SET amount = amount * 2"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM postings"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM journal_entries"))
                .hasMessageContaining("append-only");
    }

    @Test
    void databaseRefusesANegativeWalletEvenIfTheAppCheckIsBypassed() {
        long alice = fundedWallet(ledger, "alice", 1_000);

        assertThatThrownBy(() -> jdbc.update("UPDATE balances SET balance = -1 WHERE account_id = ?", alice))
                .hasMessageContaining("balance_not_negative");
    }

    @Test
    @Transactional // rolled back afterwards, so the corruption never escapes this test
    void invariantCheckSpotsACachedBalanceThatDisagreesWithPostings() {
        long alice = fundedWallet(ledger, "alice", 1_000);

        jdbc.update("UPDATE balances SET balance = 999 WHERE account_id = ?", alice);

        assertThat(invariants.violations())
                .containsExactly("account " + alice + " has cached balance 999 but its postings sum to 1000");
    }

    private long system(AccountType type) {
        return ledger.systemAccount(type, "AED").id();
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
