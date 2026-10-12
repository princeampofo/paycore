package com.paycore.transfers;

import com.paycore.TestcontainersConfiguration;
import com.paycore.ledger.InsufficientFundsException;
import com.paycore.ledger.Ledger;
import com.paycore.ledger.LedgerInvariants;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.statistics.Statistics;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;

import static com.paycore.TestWallets.fundedWallet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs random sequences of transfers against the real ledger and, after every single step,
 * checks the ledger invariants and every balance against a simple in-memory model.
 *
 * The model is just a list of longs. If it says the sender can't afford a transfer, the
 * ledger must refuse it; otherwise the ledger must accept it.
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerPropertyTest {

    private static final int WALLETS = 4;

    /** Move {@code amount} minor units from wallet number {@code from} to wallet number {@code to}. */
    record Step(int from, int to, long amount) {
    }

    @Autowired
    TransferService transferService;
    @Autowired
    Ledger ledger;
    @Autowired
    LedgerInvariants invariants;

    @Property(tries = 100)
    void ledgerStaysBalancedAndWalletsNeverGoNegative(
            @ForAll @Size(WALLETS) List<@LongRange(max = 10_000) Long> startingBalances,
            @ForAll("steps") List<Step> steps) {

        List<Long> wallets = new ArrayList<>();
        List<Long> model = new ArrayList<>(startingBalances);
        for (int i = 0; i < WALLETS; i++) {
            wallets.add(fundedWallet(ledger, "wallet-" + i, startingBalances.get(i)));
        }

        for (Step step : steps) {
            TransferRequest request = new TransferRequest(
                    wallets.get(step.from()), wallets.get(step.to()), step.amount(), "AED");

            if (model.get(step.from()) < step.amount()) {
                assertThatThrownBy(() -> transferService.transferFunds(request))
                        .isInstanceOf(InsufficientFundsException.class);
                Statistics.collect("rejected: insufficient funds");
            } else {
                transferService.transferFunds(request);
                model.set(step.from(), model.get(step.from()) - step.amount());
                model.set(step.to(), model.get(step.to()) + step.amount());
                Statistics.collect("transferred");
            }

            assertThat(invariants.violations()).isEmpty();
            for (int i = 0; i < WALLETS; i++) {
                assertThat(ledger.balance(wallets.get(i)).amount()).isEqualTo(model.get(i));
            }
        }
    }

    @Provide
    Arbitrary<List<Step>> steps() {
        Arbitrary<Integer> wallet = Arbitraries.integers().between(0, WALLETS - 1);
        Arbitrary<Step> step = Combinators.combine(wallet, wallet, Arbitraries.longs().between(1, 5_000))
                .filter((from, to, amount) -> !from.equals(to))
                .as(Step::new);
        return step.list().ofMaxSize(20);
    }
}
