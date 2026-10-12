package com.paycore.metrics;

import com.paycore.TestcontainersConfiguration;
import com.paycore.ledger.InsufficientFundsException;
import com.paycore.ledger.Ledger;
import com.paycore.mockprocessor.MockProcessor;
import com.paycore.payments.CreatePaymentRequest;
import com.paycore.payments.PaymentService;
import com.paycore.payments.PaymentStatusChecker;
import com.paycore.transfers.TransferRequest;
import com.paycore.transfers.TransferService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.paycore.TestWallets.fundedWallet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false) // tests turn metrics export off by default; this class needs it
@Import(TestcontainersConfiguration.class)
class PaycoreMetricsTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    MeterRegistry registry;
    @Autowired
    PaycoreMetrics metrics;
    @Autowired
    Ledger ledger;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PaymentService paymentService;
    @Autowired
    TransferService transferService;
    @Autowired
    PaymentStatusChecker statusChecker;
    @Autowired
    MockProcessor processor;

    @AfterEach
    void processorBackToNormal() {
        processor.setTimeoutRate(0);
    }

    @Test
    void prometheusEndpointServesThePaymentsMetrics() throws Exception {
        long cafe = fundedWallet(ledger, "corner-cafe", 0);
        paymentService.capture(pay(cafe, 1_000));
        metrics.refresh();

        String scrape = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(scrape).contains(
                "payments_total{state=\"captured\"}",
                "processor_requests_total{outcome=\"charged\",type=\"charge\"}",
                "payments_unknown ",
                "outbox_pending ",
                "outbox_oldest_age_seconds ",
                "ledger_imbalance 0.0",
                "http_server_requests_seconds_bucket");
    }

    @Test
    void paymentOutcomesAreCounted() {
        long cafe = fundedWallet(ledger, "corner-cafe", 0);
        double authorized = count("payments", "state", "authorized");
        double captured = count("payments", "state", "captured");

        paymentService.capture(pay(cafe, 1_000));
        pay(cafe, 1_000);

        assertThat(count("payments", "state", "authorized")).isEqualTo(authorized + 2);
        assertThat(count("payments", "state", "captured")).isEqualTo(captured + 1);
    }

    @Test
    void aRolledBackChangeIsNotCounted() {
        long cafe = fundedWallet(ledger, "corner-cafe", 0);
        long payment = pay(cafe, 10_000);
        paymentService.capture(payment);
        transferService.transferFunds(new TransferRequest(cafe, fundedWallet(ledger, "supplier", 0), 9_000, "AED"));
        double refunded = count("payments", "state", "refunded");

        // The status update runs, then the ledger refuses the refund and everything rolls back.
        assertThatThrownBy(() -> paymentService.refund(payment)).isInstanceOf(InsufficientFundsException.class);

        assertThat(count("payments", "state", "refunded")).isEqualTo(refunded);
    }

    @Test
    void idempotentReplaysAreCounted() throws Exception {
        long alice = fundedWallet(ledger, "alice", 10_000);
        long bob = fundedWallet(ledger, "bob", 0);
        String key = UUID.randomUUID().toString();
        double before = count("idempotent.replays");

        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/v1/transfers").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key)
                            .content("{\"from_wallet_id\": %d, \"to_wallet_id\": %d, \"amount\": 100, \"currency\": \"AED\"}"
                                    .formatted(alice, bob)))
                    .andExpect(status().isCreated());
        }

        assertThat(count("idempotent.replays")).isEqualTo(before + 2); // the first one did the work
    }

    @Test
    void unknownPaymentsGoUpOnATimeoutAndDrainBackToZeroOnceResolved() {
        long cafe = fundedWallet(ledger, "corner-cafe", 0);
        statusChecker.resolveUnknownPayments(); // clear anything earlier tests left behind
        metrics.refresh();
        assertThat(gauge("payments.unknown")).isZero();

        processor.setTimeoutRate(1.0);
        pay(cafe, 1_000);
        pay(cafe, 1_000);
        metrics.refresh();
        assertThat(gauge("payments.unknown")).isEqualTo(2);
        assertThat(count("processor.requests", "outcome", "unknown")).isPositive();

        processor.setTimeoutRate(0);
        statusChecker.resolveUnknownPayments();
        metrics.refresh();
        assertThat(gauge("payments.unknown")).isZero();
    }

    @Test
    @Transactional // rolled back afterwards, so the corruption never escapes this test
    void ledgerImbalanceCountsInvariantViolations() {
        long alice = fundedWallet(ledger, "alice", 1_000);
        metrics.refresh();
        assertThat(gauge("ledger.imbalance")).isZero();

        jdbc.update("UPDATE balances SET balance = 999 WHERE account_id = ?", alice);
        metrics.refresh();

        assertThat(gauge("ledger.imbalance")).isEqualTo(1);
    }

    private long pay(long merchant, long amount) {
        return paymentService.createPayment(new CreatePaymentRequest(merchant, amount, "AED", "tok_visa")).id();
    }

    private double count(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }
}
