package com.paycore.idempotency;

import com.paycore.TestcontainersConfiguration;
import com.paycore.ledger.Ledger;
import com.paycore.ledger.LedgerInvariants;
import com.paycore.ledger.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.paycore.TestWallets.fundedWallet;
import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP against a running server, the way a client that retries aggressively would hit it. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class DuplicateRequestsTest {

    @LocalServerPort
    int port;
    @Autowired
    Ledger ledger;
    @Autowired
    LedgerInvariants invariants;
    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Test
    void fiftyConcurrentDuplicatesChargeExactlyOnce() throws Exception {
        long alice = fundedWallet(ledger, "alice", 10_000);
        long bob = fundedWallet(ledger, "bob", 0);
        HttpRequest sameRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/transfers"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"from_wallet_id": %d, "to_wallet_id": %d, "amount": 1000, "currency": "AED"}
                        """.formatted(alice, bob)))
                .build();

        AtomicInteger conflicts = new AtomicInteger();
        List<HttpResponse<String>> responses = sendAllAtOnce(50, sameRequest, conflicts);

        // Every client ends up with the same 201 and the same body...
        assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(201));
        assertThat(responses.stream().map(HttpResponse::body).distinct()).hasSize(1);
        // ...and the money moved exactly once.
        assertThat(ledger.balance(alice)).isEqualTo(Money.of(9_000, "AED"));
        assertThat(ledger.balance(bob)).isEqualTo(Money.of(1_000, "AED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM postings WHERE account_id = ?", Long.class, bob))
                .isEqualTo(1);
        assertThat(invariants.violations()).isEmpty();
        System.out.println("409 request_in_progress responses absorbed by retrying: " + conflicts.get());
    }

    /**
     * Sends the same request from {@code clients} threads released at the same instant. Each client
     * behaves like a well-written one: on 409 ("still in progress") it waits briefly and retries.
     */
    private List<HttpResponse<String>> sendAllAtOnce(int clients, HttpRequest request, AtomicInteger conflicts)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    while (true) {
                        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() != 409) {
                            return response;
                        }
                        conflicts.incrementAndGet();
                        Thread.sleep(10);
                    }
                }));
            }
            start.countDown();

            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }
}
