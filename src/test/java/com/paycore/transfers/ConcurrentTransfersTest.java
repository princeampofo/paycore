package com.paycore.transfers;

import com.paycore.TestcontainersConfiguration;
import com.paycore.ledger.InsufficientFundsException;
import com.paycore.ledger.Ledger;
import com.paycore.ledger.LedgerInvariants;
import com.paycore.ledger.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.paycore.TestWallets.fundedWallet;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConcurrentTransfersTest {

    @Autowired
    TransferService transferService;
    @Autowired
    Ledger ledger;
    @Autowired
    LedgerInvariants invariants;

    @Test
    void concurrentTransfersNeverOverdraw() throws Exception {
        // Alice has 500.00 AED and 1,000 transfers of 1.00 AED race to spend it.
        long alice = fundedWallet(ledger, "alice", 50_000);
        List<Long> recipients = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            recipients.add(fundedWallet(ledger, "recipient-" + i, 0));
        }

        List<Task> transfers = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            long to = recipients.get(i % recipients.size());
            transfers.add(() -> transferService.transferFunds(new TransferRequest(alice, to, 100, "AED")));
        }
        List<String> outcomes = runAllAtOnce(transfers);

        // Exactly the money she had moved: 500 succeeded, the other 500 were refused cleanly.
        assertThat(outcomes).filteredOn("ok"::equals).hasSize(500);
        assertThat(outcomes).filteredOn("insufficient_funds"::equals).hasSize(500);
        assertThat(ledger.balance(alice)).isEqualTo(Money.of(0, "AED"));
        long received = recipients.stream().mapToLong(id -> ledger.balance(id).amount()).sum();
        assertThat(received).isEqualTo(50_000);
        assertThat(invariants.violations()).isEmpty();
    }

    @Test
    void opposingTransfersDoNotDeadlock() throws Exception {
        // A→B and B→A at the same time is the classic deadlock; lock ordering prevents it.
        long alice = fundedWallet(ledger, "alice", 100_000);
        long bob = fundedWallet(ledger, "bob", 100_000);

        List<Task> transfers = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            transfers.add(() -> transferService.transferFunds(new TransferRequest(alice, bob, 100, "AED")));
            transfers.add(() -> transferService.transferFunds(new TransferRequest(bob, alice, 100, "AED")));
        }
        List<String> outcomes = runAllAtOnce(transfers);

        assertThat(outcomes).containsOnly("ok");
        assertThat(ledger.balance(alice)).isEqualTo(Money.of(100_000, "AED"));
        assertThat(ledger.balance(bob)).isEqualTo(Money.of(100_000, "AED"));
        assertThat(invariants.violations()).isEmpty();
    }

    interface Task {
        void run() throws Exception;
    }

    /**
     * Runs every task on its own pool thread, all released by one starting gun, and returns
     * "ok" or "insufficient_funds" per task. Any other failure (e.g. a deadlock) fails the test.
     */
    private static List<String> runAllAtOnce(List<Task> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(64);
        try {
            // Results arrive in the order tasks finish, so the first failure is reported right away.
            CompletionService<String> finished = new ExecutorCompletionService<>(pool);
            for (Task task : tasks) {
                finished.submit(() -> {
                    start.await();
                    try {
                        task.run();
                        return "ok";
                    } catch (InsufficientFundsException e) {
                        return "insufficient_funds";
                    }
                });
            }
            start.countDown();

            List<String> outcomes = new ArrayList<>();
            for (int i = 0; i < tasks.size(); i++) {
                Future<String> next = finished.poll(30, TimeUnit.SECONDS);
                if (next == null) {
                    throw new AssertionError("No transfer finished in 30 seconds");
                }
                outcomes.add(next.get()); // rethrows anything unexpected
            }
            return outcomes;
        } finally {
            pool.shutdownNow(); // don't wait for the remaining tasks once we have a failure
        }
    }
}
