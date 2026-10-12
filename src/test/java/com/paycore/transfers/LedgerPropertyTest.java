package com.paycore.transfers;

import com.paycore.TestcontainersConfiguration;
import com.paycore.ledger.AccountType;
import com.paycore.ledger.InsufficientFundsException;
import com.paycore.ledger.Ledger;
import com.paycore.ledger.LedgerInvariants;
import com.paycore.payments.CreatePaymentRequest;
import com.paycore.payments.IllegalTransitionException;
import com.paycore.payments.PaymentResponse;
import com.paycore.payments.PaymentService;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
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
 * Runs random sequences of transfers, card payments, captures, refunds and cancels against the
 * real ledger and, after every single step, checks the ledger invariants and every balance and
 * payment status against a simple in-memory model.
 *
 * The model is a few plain lists. It predicts what each step must do: succeed, or be refused
 * for insufficient funds or an illegal state transition. The real system must agree every time.
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerPropertyTest {

    private static final int WALLETS = 4;

    sealed interface Step {
    }

    record Transfer(int from, int to, long amount) implements Step {
    }

    /** A card payment to wallet {@code merchant}, captured straight away or held for a later capture. */
    record Pay(int merchant, long amount, boolean declined, boolean automaticCapture) implements Step {
    }

    /** Capture, refund or cancel payment number {@code payment} (modulo how many exist so far). */
    record Act(String action, int payment) implements Step {
    }

    static final class ModelPayment {
        final long id;
        final int merchant;
        final long amount;
        String status;

        ModelPayment(long id, int merchant, long amount, String status) {
            this.id = id;
            this.merchant = merchant;
            this.amount = amount;
            this.status = status;
        }

        long netToMerchant() {
            return amount - amount * 290 / 10_000; // 2.90% fee, rounded down
        }
    }

    @Autowired
    TransferService transferService;
    @Autowired
    PaymentService paymentService;
    @Autowired
    Ledger ledger;
    @Autowired
    LedgerInvariants invariants;

    private List<Long> wallets;
    private List<Long> balances;
    private List<ModelPayment> payments;
    private long holds;

    @Property(tries = 100)
    void ledgerStaysBalancedAndWalletsNeverGoNegative(
            @ForAll @Size(WALLETS) List<@LongRange(max = 10_000) Long> startingBalances,
            @ForAll("steps") List<Step> steps) {

        wallets = new ArrayList<>();
        balances = new ArrayList<>(startingBalances);
        payments = new ArrayList<>();
        holds = holdsBalance();
        for (int i = 0; i < WALLETS; i++) {
            wallets.add(fundedWallet(ledger, "wallet-" + i, startingBalances.get(i)));
        }

        for (Step step : steps) {
            switch (step) {
                case Transfer transfer -> transfer(transfer);
                case Pay pay -> pay(pay);
                case Act act -> act(act);
            }

            assertThat(invariants.violations()).isEmpty();
            for (int i = 0; i < WALLETS; i++) {
                assertThat(ledger.balance(wallets.get(i)).amount()).isEqualTo(balances.get(i));
            }
            assertThat(holdsBalance()).isEqualTo(holds);
            for (ModelPayment payment : payments) {
                assertThat(paymentService.get(payment.id).status()).isEqualTo(payment.status);
            }
        }
    }

    private void transfer(Transfer step) {
        TransferRequest request = new TransferRequest(wallets.get(step.from()), wallets.get(step.to()), step.amount(), "AED");
        if (balances.get(step.from()) < step.amount()) {
            assertThatThrownBy(() -> transferService.transferFunds(request)).isInstanceOf(InsufficientFundsException.class);
            Statistics.collect("transfer refused: insufficient funds");
            return;
        }
        transferService.transferFunds(request);
        add(step.from(), -step.amount());
        add(step.to(), step.amount());
        Statistics.collect("transfer");
    }

    private void pay(Pay step) {
        String token = step.declined() ? "tok_declined" : "tok_visa";
        String captureMethod = step.automaticCapture() ? "automatic" : "manual";
        PaymentResponse response = paymentService.createPayment(new CreatePaymentRequest(
                wallets.get(step.merchant()), step.amount(), "AED", token, null, captureMethod));

        ModelPayment payment = new ModelPayment(response.id(), step.merchant(), step.amount(), "failed");
        if (!step.declined() && step.automaticCapture()) {
            payment.status = "captured"; // the money passes through holds and straight on to the merchant
            add(step.merchant(), payment.netToMerchant());
        } else if (!step.declined()) {
            payment.status = "authorized";
            holds += step.amount();
        }
        assertThat(response.status()).isEqualTo(payment.status);
        payments.add(payment);
        Statistics.collect("pay (" + captureMethod + "): " + payment.status);
    }

    private void act(Act step) {
        if (payments.isEmpty()) {
            Statistics.collect(step.action() + ": no payment yet");
            return;
        }
        ModelPayment payment = payments.get(step.payment() % payments.size());
        switch (step.action()) {
            case "capture" -> {
                if (!expectLegal(payment, "authorized", () -> paymentService.capture(payment.id))) {
                    return;
                }
                holds -= payment.amount;
                add(payment.merchant, payment.netToMerchant());
                payment.status = "captured";
                Statistics.collect("capture");
            }
            case "cancel" -> {
                if (!expectLegal(payment, "authorized", () -> paymentService.cancel(payment.id))) {
                    return;
                }
                holds -= payment.amount;
                payment.status = "canceled";
                Statistics.collect("cancel");
            }
            case "refund" -> {
                if (payment.status.equals("captured") && balances.get(payment.merchant) < payment.netToMerchant()) {
                    // The merchant already spent the money, so it can't be taken back.
                    assertThatThrownBy(() -> paymentService.refund(payment.id)).isInstanceOf(InsufficientFundsException.class);
                    Statistics.collect("refund refused: merchant spent the money");
                    return;
                }
                if (!expectLegal(payment, "captured", () -> paymentService.refund(payment.id))) {
                    return;
                }
                add(payment.merchant, -payment.netToMerchant());
                payment.status = "refunded";
                Statistics.collect("refund");
            }
            default -> throw new IllegalStateException(step.action());
        }
    }

    /** Runs {@code action} if the payment is in {@code requiredStatus}; otherwise expects it to be refused. */
    private boolean expectLegal(ModelPayment payment, String requiredStatus, Runnable action) {
        if (!payment.status.equals(requiredStatus)) {
            assertThatThrownBy(action::run).isInstanceOf(IllegalTransitionException.class);
            Statistics.collect(payment.status + " payment refused: illegal transition");
            return false;
        }
        action.run();
        return true;
    }

    private void add(int wallet, long amount) {
        balances.set(wallet, balances.get(wallet) + amount);
    }

    private long holdsBalance() {
        return ledger.balance(ledger.systemAccount(AccountType.HOLDS, "AED").id()).amount();
    }

    @Provide
    Arbitrary<List<Step>> steps() {
        Arbitrary<Integer> wallet = Arbitraries.integers().between(0, WALLETS - 1);
        Arbitrary<Long> amount = Arbitraries.longs().between(1, 5_000);

        Arbitrary<Step> transfer = Combinators.combine(wallet, wallet, amount)
                .filter((from, to, a) -> !from.equals(to))
                .as(Transfer::new);
        Arbitrary<Step> pay = Combinators.combine(wallet, amount, Arbitraries.integers().between(1, 10), Arbitraries.of(true, false))
                .as((merchant, a, roll, automatic) -> new Pay(merchant, a, roll == 1, automatic)); // 1 in 10 cards is declined
        Arbitrary<Step> act = Combinators.combine(
                        Arbitraries.frequency(Tuple.of(3, "capture"), Tuple.of(2, "refund"), Tuple.of(1, "cancel")),
                        Arbitraries.integers().between(0, 9))
                .as(Act::new);

        return Arbitraries.frequencyOf(Tuple.of(3, transfer), Tuple.of(2, pay), Tuple.of(3, act))
                .list().ofMinSize(10).ofMaxSize(30);
    }
}
