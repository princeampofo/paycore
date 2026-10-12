package com.paycore.metrics;

import com.paycore.ledger.LedgerInvariants;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The payments-specific metrics, served at /actuator/prometheus next to the standard HTTP and JVM ones.
 *
 *   payments_total{state}                 payment outcomes over time
 *   payments_unknown                      payments stuck after processor timeouts; should drain to 0
 *   idempotent_replays_total              retries answered from a saved response
 *   processor_requests_total{type,outcome} charges and status checks, by result
 *   outbox_pending                        webhooks waiting to be delivered
 *   outbox_oldest_age_seconds             how long the oldest of them has waited
 *   ledger_imbalance                      ledger invariant violations; must always be 0
 */
@Component
public class PaycoreMetrics {

    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;
    private final LedgerInvariants invariants;

    // The gauges read these; refresh() recomputes them from the database. Doing it on a schedule
    // (instead of on every scrape) keeps the cost fixed, however often Prometheus asks.
    private final AtomicLong unknownPayments = new AtomicLong();
    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong outboxOldestAgeSeconds = new AtomicLong();
    private final AtomicLong ledgerImbalance = new AtomicLong();

    public PaycoreMetrics(MeterRegistry registry, JdbcTemplate jdbc, LedgerInvariants invariants) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.invariants = invariants;
        Gauge.builder("payments.unknown", unknownPayments, AtomicLong::get)
                .description("Payments whose outcome we're still finding out from the processor")
                .register(registry);
        Gauge.builder("outbox.pending", outboxPending, AtomicLong::get)
                .description("Webhook events not yet delivered")
                .register(registry);
        Gauge.builder("outbox.oldest.age", outboxOldestAgeSeconds, AtomicLong::get)
                .baseUnit("seconds")
                .description("Age of the oldest undelivered webhook event")
                .register(registry);
        Gauge.builder("ledger.imbalance", ledgerImbalance, AtomicLong::get)
                .description("Number of ledger invariant violations; anything but 0 is a bug")
                .register(registry);
    }

    /** Counted only once the transaction commits, so a rolled-back change is never counted. */
    public void paymentMovedTo(String state) {
        Counter counter = Counter.builder("payments").tag("state", state)
                .description("Payments reaching each state").register(registry);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    counter.increment();
                }
            });
        } else {
            counter.increment();
        }
    }

    public void idempotentReplay() {
        Counter.builder("idempotent.replays").description("Retries answered with a saved response")
                .register(registry).increment();
    }

    /** {@code type} is "charge" or "status"; {@code outcome} is charged, not_charged or unknown. */
    public void processorRequest(String type, String outcome) {
        Counter.builder("processor.requests").tag("type", type).tag("outcome", outcome)
                .description("Requests to the card processor").register(registry).increment();
    }

    @Scheduled(fixedDelayString = "${paycore.metrics.refresh-interval-ms}")
    public void refresh() {
        unknownPayments.set(jdbc.queryForObject(
                "SELECT count(*) FROM payments WHERE status = 'UNKNOWN'", Long.class));
        jdbc.query("""
                SELECT count(*) AS pending, COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0) AS oldest
                FROM outbox WHERE status = 'pending'""",
                rs -> {
                    outboxPending.set(rs.getLong("pending"));
                    outboxOldestAgeSeconds.set(rs.getLong("oldest"));
                });
        ledgerImbalance.set(invariants.violations().size());
    }
}
