package com.paycore.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.TestcontainersConfiguration;
import com.paycore.ledger.Ledger;
import com.paycore.ledger.LedgerInvariants;
import com.paycore.ledger.Money;
import com.paycore.mockprocessor.MockProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.paycore.TestWallets.fundedWallet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The key scenario: the processor charges the card, but its answer never reaches us. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProcessorTimeoutTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    Ledger ledger;
    @Autowired
    LedgerInvariants invariants;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MockProcessor processor;
    @Autowired
    ProcessorClient processorClient;
    @Autowired
    PaymentRepository payments;
    @Autowired
    PaymentService paymentService;
    @Autowired
    PaymentStatusChecker statusChecker;

    @AfterEach
    void processorBackToNormal() {
        processor.setTimeoutRate(0);
        assertThat(invariants.violations()).isEmpty();
    }

    @Test
    void aChargeThatTimesOutIsResolvedAndTheCustomerIsChargedExactlyOnce() throws Exception {
        long cafe = fundedWallet(ledger, "corner-cafe", 0);
        int chargesBefore = processor.approvedCharges();
        processor.setTimeoutRate(1.0); // charge the card, then answer far too late

        long payment = pay(newKey(), cafe, "tok_visa", "unknown");

        // The card WAS charged, but we don't know that yet, so our ledger hasn't moved any money.
        assertThat(processor.approvedCharges()).isEqualTo(chargesBefore + 1);
        assertThat(authorizeEntries(payment)).isZero();

        processor.setTimeoutRate(0);
        statusChecker.resolveUnknownPayments();

        // The check found the real answer: authorized, recorded once, and no second charge.
        assertThat(paymentService.get(payment).status()).isEqualTo("authorized");
        assertThat(authorizeEntries(payment)).isEqualTo(1);
        assertThat(processor.approvedCharges()).isEqualTo(chargesBefore + 1);

        // Running the check again changes nothing.
        statusChecker.resolveUnknownPayments();
        assertThat(authorizeEntries(payment)).isEqualTo(1);

        // And the payment carries on as normal.
        paymentService.capture(payment);
        assertThat(ledger.balance(cafe)).isEqualTo(Money.of(9_710, "AED"));
    }

    @Test
    void aClientRetryingAfterTheTimeoutGetsTheSameAnswerAndNoNewCharge() throws Exception {
        long cafe = fundedWallet(ledger, "corner-cafe", 0);
        int chargesBefore = processor.approvedCharges();
        processor.setTimeoutRate(1.0);
        String key = newKey();

        long first = pay(key, cafe, "tok_visa", "unknown");
        long retry = pay(key, cafe, "tok_visa", "unknown");

        assertThat(retry).isEqualTo(first);
        assertThat(processor.approvedCharges()).isEqualTo(chargesBefore + 1);
    }

    @Test
    void aTimedOutChargeThatWasActuallyDeclinedResolvesToFailed() throws Exception {
        long cafe = fundedWallet(ledger, "corner-cafe", 0);
        processor.setTimeoutRate(1.0);
        long payment = pay(newKey(), cafe, "tok_declined", "unknown");

        processor.setTimeoutRate(0);
        statusChecker.resolveUnknownPayments();

        assertThat(paymentService.get(payment).status()).isEqualTo("failed");
        assertThat(authorizeEntries(payment)).isZero();
    }

    @Test
    void aChargeTheProcessorNeverReceivedResolvesToFailed() {
        long cafe = fundedWallet(ledger, "corner-cafe", 0);
        // The request was lost on the way: we have a payment and a ref, the processor has nothing.
        Payment lost = payments.insert(cafe, Money.of(10_000, "AED"), "tok_visa", "pay_" + UUID.randomUUID());
        payments.updateStatus(lost, PaymentStatus.UNKNOWN, lost.fee());

        statusChecker.resolveUnknownPayments();

        assertThat(paymentService.get(lost.id()).status()).isEqualTo("failed");
    }

    @Test
    void aPaymentLeftCreatedByACrashIsResolvedToo() {
        long cafe = fundedWallet(ledger, "corner-cafe", 0);
        // We saved the payment and the processor charged it, then we died before recording the answer.
        Payment crashed = payments.insert(cafe, Money.of(10_000, "AED"), "tok_visa", "pay_" + UUID.randomUUID());
        processorClient.charge(crashed.processorRef(), crashed.amount(), crashed.cardToken());

        statusChecker.resolveUnknownPayments();

        assertThat(paymentService.get(crashed.id()).status()).isEqualTo("authorized");
        assertThat(authorizeEntries(crashed.id())).isEqualTo(1);
    }

    private long pay(String key, long merchant, String cardToken, String expectedStatus) throws Exception {
        String body = mvc.perform(post("/v1/payments").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content("""
                                {"merchant_wallet_id": %d, "amount": 10000, "currency": "AED", "card_token": "%s"}
                                """.formatted(merchant, cardToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    private long authorizeEntries(long paymentId) {
        return jdbc.queryForObject("SELECT count(*) FROM journal_entries WHERE description = ?",
                Long.class, "authorize payment " + paymentId);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }
}
