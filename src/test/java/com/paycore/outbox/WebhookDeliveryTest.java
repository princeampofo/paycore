package com.paycore.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.TestcontainersConfiguration;
import com.paycore.ledger.InsufficientFundsException;
import com.paycore.ledger.Ledger;
import com.paycore.outbox.TestWebhookReceiver.Delivery;
import com.paycore.outbox.TestWebhookReceiver.Mode;
import com.paycore.payments.CreatePaymentRequest;
import com.paycore.payments.PaymentService;
import com.paycore.transfers.TransferRequest;
import com.paycore.transfers.TransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.paycore.TestWallets.fundedWallet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WebhookDeliveryTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Ledger ledger;
    @Autowired
    PaymentService paymentService;
    @Autowired
    TransferService transferService;
    @Autowired
    WebhookDispatcher dispatcher;

    private TestWebhookReceiver receiver;
    private long cafe;
    private long endpointId;

    @BeforeEach
    void registerTheCafesEndpoint() throws Exception {
        receiver = new TestWebhookReceiver();
        cafe = fundedWallet(ledger, "corner-cafe", 0);
        String body = mvc.perform(post("/v1/webhook_endpoints").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content("{\"merchant_wallet_id\": %d, \"url\": \"%s\"}".formatted(cafe, receiver.url())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode endpoint = json.readTree(body);
        endpointId = endpoint.get("id").asLong();
        receiver.setSecret(endpoint.get("secret").asText());
    }

    @AfterEach
    void stopReceiver() {
        // Nothing of ours may be left for later tests' dispatcher runs to trip over.
        assertThat(count("status = 'pending'")).isZero();
        receiver.close();
    }

    @Test
    void webhooksSurviveAReceiverOutageWithNoLostEvents() throws Exception {
        receiver.setMode(Mode.DOWN);
        // 10 payments: 5 captured, 5 canceled. That's 20 events: 10 authorized, 5 captured, 5 canceled.
        for (int i = 0; i < 10; i++) {
            long payment = pay(1_000).id();
            if (i % 2 == 0) {
                paymentService.capture(payment);
            } else {
                paymentService.cancel(payment);
            }
        }

        // The café is down: every attempt fails, nothing gets through, but nothing is dropped either.
        for (int i = 0; i < 3; i++) {
            assertThat(dispatcher.dispatchDue()).isZero();
        }
        assertThat(receiver.deliveries).isEmpty();
        assertThat(count("status = 'pending' AND attempts = 3")).isEqualTo(20);

        receiver.setMode(Mode.UP);
        dispatchUntilNothingIsPending();

        // Every event we recorded reached the café, each one correctly signed.
        assertThat(receiver.processedEventIds).containsExactlyInAnyOrderElementsOf(eventIds());
        assertThat(receiver.processedEventIds).hasSize(20);
        assertThat(receiver.deliveries).allMatch(Delivery::signatureValid);
        assertThat(count("status = 'delivered'")).isEqualTo(20);
        assertThat(receiver.deliveries.stream().map(this::type))
                .filteredOn("payment.captured"::equals).hasSize(5);
    }

    @Test
    void anEventIsWrittenOnlyIfItsChangeCommits() {
        long payment = pay(10_000).id();
        paymentService.capture(payment);
        // The café spends the money, so the refund is refused and its transaction rolls back.
        long supplier = fundedWallet(ledger, "supplier", 0);
        transferService.transferFunds(new TransferRequest(cafe, supplier, 9_000, "AED"));

        assertThatThrownBy(() -> paymentService.refund(payment)).isInstanceOf(InsufficientFundsException.class);

        assertThat(types()).containsExactly("payment.authorized", "payment.captured"); // no payment.refunded
        dispatchUntilNothingIsPending();
    }

    @Test
    void anAutomaticallyCapturedPaymentSendsAuthorizedThenCaptured() {
        paymentService.createPayment(new CreatePaymentRequest(cafe, 1_000, "AED", "tok_visa", null, "automatic"));

        assertThat(types()).containsExactly("payment.authorized", "payment.captured");
        dispatchUntilNothingIsPending();
        assertThat(receiver.processedEventIds).hasSize(2);
    }

    @Test
    void aWebhookThatKeepsFailingIsMarkedDeadAfterMaxAttempts() {
        receiver.setMode(Mode.DOWN);
        pay(1_000);

        for (int i = 0; i < 6; i++) { // max-attempts is 5 in tests; the 6th run finds nothing to do
            dispatcher.dispatchDue();
        }

        assertThat(count("status = 'dead' AND attempts = 5 AND last_error LIKE '%503%'")).isEqualTo(1);
    }

    @Test
    void aWebhookProcessedButNotAcknowledgedIsSentAgainAndTheReceiverIgnoresTheDuplicate() {
        receiver.setMode(Mode.PROCESS_THEN_FAIL);
        pay(1_000);

        dispatcher.dispatchDue();
        receiver.setMode(Mode.UP);
        dispatchUntilNothingIsPending();

        // At-least-once: the same event arrived twice. Dedupe by event id: it was acted on once.
        assertThat(receiver.deliveries).hasSize(2);
        assertThat(receiver.deliveries.get(0).eventId()).isEqualTo(receiver.deliveries.get(1).eventId());
        assertThat(receiver.processedEventIds).hasSize(1);
    }

    @Test
    void concurrentDispatchersNeverSendTheSameEventTwice() throws Exception {
        for (int i = 0; i < 30; i++) {
            pay(1_000);
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return dispatcher.dispatchDue();
                }));
            }
            start.countDown();
            for (Future<Integer> run : runs) {
                run.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        dispatchUntilNothingIsPending();

        assertThat(receiver.deliveries).hasSize(30);
        assertThat(new HashSet<>(receiver.deliveries.stream().map(Delivery::eventId).toList())).hasSize(30);
    }

    @Test
    void endpointsMustBeAnHttpUrlForAnExistingWallet() throws Exception {
        register(cafe, "not a url").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_url"));
        register(cafe, "ftp://cafe.example/hooks").andExpect(jsonPath("$.code").value("invalid_url"));
        register(999_999_999L, receiver.url()).andExpect(status().isNotFound());
    }

    private com.paycore.payments.PaymentResponse pay(long amount) {
        return paymentService.createPayment(new CreatePaymentRequest(cafe, amount, "AED", "tok_visa", null, "manual"));
    }

    private org.springframework.test.web.servlet.ResultActions register(long wallet, String url) throws Exception {
        return mvc.perform(post("/v1/webhook_endpoints").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"merchant_wallet_id\": %d, \"url\": \"%s\"}".formatted(wallet, url)));
    }

    private void dispatchUntilNothingIsPending() {
        for (int i = 0; i < 20 && count("status = 'pending'") > 0; i++) {
            dispatcher.dispatchDue();
        }
    }

    private long count(String condition) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE endpoint_id = ? AND " + condition,
                Long.class, endpointId);
    }

    private List<String> eventIds() {
        return jdbc.queryForList("SELECT event_id FROM outbox WHERE endpoint_id = ?", String.class, endpointId);
    }

    private List<String> types() {
        return jdbc.queryForList("SELECT type FROM outbox WHERE endpoint_id = ? ORDER BY id", String.class, endpointId);
    }

    private String type(Delivery delivery) {
        try {
            return json.readTree(delivery.body()).get("type").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
