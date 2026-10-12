package com.paycore.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.TestcontainersConfiguration;
import com.paycore.api.ApiException;
import com.paycore.ledger.AccountType;
import com.paycore.ledger.Ledger;
import com.paycore.ledger.LedgerInvariants;
import com.paycore.mockprocessor.MockProcessor;
import com.paycore.transfers.TransferRequest;
import com.paycore.transfers.TransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.paycore.TestWallets.fundedWallet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A customer pays the Corner Café 100.00 AED by card, through the HTTP API. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentLifecycleTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    Ledger ledger;
    @Autowired
    LedgerInvariants invariants;
    @Autowired
    PaymentService paymentService;
    @Autowired
    TransferService transferService;
    @Autowired
    MockProcessor processor;

    @AfterEach
    void processorBackToNormal() {
        processor.setTimeoutRate(0);
        assertThat(invariants.violations()).isEmpty();
    }

    @Test
    void authorizingChargesTheCardAndHoldsTheMoney() throws Exception {
        long cafe = fundedWallet(ledger, "corner-cafe", 0);
        long holdsBefore = balance(AccountType.HOLDS);

        long payment = createPayment(cafe, 10_000, "tok_visa", "authorized");

        assertThat(balance(AccountType.HOLDS)).isEqualTo(holdsBefore + 10_000);
        assertThat(ledger.balance(cafe).amount()).isZero(); // not the merchant's until captured
        mvc.perform(get("/v1/payments/{id}", payment)).andExpect(jsonPath("$.status").value("authorized"));
    }

    @Test
    void captureMovesTheHeldMoneyToTheMerchantMinusTheFee() throws Exception {
        long cafe = fundedWallet(ledger, "corner-cafe", 0);
        long holdsBefore = balance(AccountType.HOLDS);
        long feesBefore = balance(AccountType.FEE_REVENUE);
        long payment = createPayment(cafe, 10_000, "tok_visa", "authorized");

        act(payment, "capture")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("captured"))
                .andExpect(jsonPath("$.fee").value(290));

        // 100.00 AED: 97.10 to the café, 2.90 platform fee (2.90%).
        assertThat(ledger.balance(cafe).amount()).isEqualTo(9_710);
        assertThat(balance(AccountType.FEE_REVENUE)).isEqualTo(feesBefore + 290);
        assertThat(balance(AccountType.HOLDS)).isEqualTo(holdsBefore);
    }

    @Test
    void refundReversesTheCaptureAndReturnsTheMoneyToTheCard() throws Exception {
        long cafe = fundedWallet(ledger, "corner-cafe", 0);
        long feesBefore = balance(AccountType.FEE_REVENUE);
        long fundingBefore = balance(AccountType.EXTERNAL_FUNDING);
        long payment = createPayment(cafe, 10_000, "tok_visa", "authorized");
        act(payment, "capture").andExpect(status().isOk());

        act(payment, "refund").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("refunded"));

        assertThat(ledger.balance(cafe).amount()).isZero();
        assertThat(balance(AccountType.FEE_REVENUE)).isEqualTo(feesBefore);
        assertThat(balance(AccountType.EXTERNAL_FUNDING)).isEqualTo(fundingBefore);
    }

    @Test
    void refundIsRefusedIfTheMerchantAlreadySpentTheMoney() throws Exception {
        long cafe = fundedWallet(ledger, "corner-cafe", 0);
        long supplier = fundedWallet(ledger, "supplier", 0);
        long payment = createPayment(cafe, 10_000, "tok_visa", "authorized");
        act(payment, "capture").andExpect(status().isOk());
        transferService.transferFunds(new TransferRequest(cafe, supplier, 9_000, "AED"));

        act(payment, "refund")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("insufficient_funds"));

        mvc.perform(get("/v1/payments/{id}", payment)).andExpect(jsonPath("$.status").value("captured"));
        assertThat(ledger.balance(cafe).amount()).isEqualTo(710);
    }

    @Test
    void cancelReleasesTheHoldWithoutPayingTheMerchant() throws Exception {
        long cafe = fundedWallet(ledger, "corner-cafe", 0);
        long holdsBefore = balance(AccountType.HOLDS);
        long fundingBefore = balance(AccountType.EXTERNAL_FUNDING);
        long payment = createPayment(cafe, 10_000, "tok_visa", "authorized");

        act(payment, "cancel").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("canceled"));

        assertThat(balance(AccountType.HOLDS)).isEqualTo(holdsBefore);
        assertThat(balance(AccountType.EXTERNAL_FUNDING)).isEqualTo(fundingBefore);
        assertThat(ledger.balance(cafe).amount()).isZero();
    }

    @Test
    void aDeclinedCardFailsThePaymentAndMovesNoMoney() throws Exception {
        long cafe = fundedWallet(ledger, "corner-cafe", 0);
        long holdsBefore = balance(AccountType.HOLDS);

        createPayment(cafe, 10_000, "tok_declined", "failed");

        assertThat(balance(AccountType.HOLDS)).isEqualTo(holdsBefore);
    }

    @Test
    void aProcessorTimeoutLeavesThePaymentUnknownInsteadOfGuessing() throws Exception {
        long cafe = fundedWallet(ledger, "corner-cafe", 0);
        long holdsBefore = balance(AccountType.HOLDS);
        processor.setTimeoutRate(1.0); // charges the card, then never answers in time

        createPayment(cafe, 10_000, "tok_visa", "unknown");

        assertThat(balance(AccountType.HOLDS)).isEqualTo(holdsBefore);
    }

    @Test
    void illegalTransitionsAreRejected() throws Exception {
        long cafe = fundedWallet(ledger, "corner-cafe", 0);
        long failed = createPayment(cafe, 1_000, "tok_declined", "failed");
        long authorized = createPayment(cafe, 1_000, "tok_visa", "authorized");
        long captured = createPayment(cafe, 1_000, "tok_visa", "authorized");
        act(captured, "capture").andExpect(status().isOk());

        expectInvalidState(act(failed, "capture"), "A payment that is failed cannot become captured");
        expectInvalidState(act(authorized, "refund"), "A payment that is authorized cannot become refunded");
        expectInvalidState(act(captured, "capture"), "A payment that is captured cannot become captured");
        expectInvalidState(act(captured, "cancel"), "A payment that is captured cannot become canceled");

        // Only the one legal capture paid the café: 10.00 AED minus the 0.29 fee.
        assertThat(ledger.balance(cafe).amount()).isEqualTo(971);
    }

    @Test
    void concurrentCapturesOfOnePaymentSucceedExactlyOnce() throws Exception {
        long cafe = fundedWallet(ledger, "corner-cafe", 0);
        long payment = createPayment(cafe, 10_000, "tok_visa", "authorized");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<String>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                outcomes.add(pool.submit(() -> {
                    start.await();
                    try {
                        paymentService.capture(payment);
                        return "captured";
                    } catch (IllegalTransitionException | ApiException e) {
                        return "refused"; // already captured, or lost the race on the version column
                    }
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> outcome : outcomes) {
                results.add(outcome.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).filteredOn("captured"::equals).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(ledger.balance(cafe).amount()).isEqualTo(9_710);
    }

    @Test
    void anythingThatLooksLikeACardNumberIsRefused() throws Exception {
        long cafe = fundedWallet(ledger, "corner-cafe", 0);

        pay(cafe, 1_000, "4242424242424242").andExpect(jsonPath("$.code").value("invalid_card_token"));
        pay(cafe, 1_000, "tok_4242424242424242").andExpect(jsonPath("$.code").value("invalid_card_token"));
    }

    @Test
    void unknownPaymentsAre404() throws Exception {
        act(999_999_999L, "capture").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("payment_not_found"));
    }

    private long createPayment(long merchant, long amount, String cardToken, String expectedStatus) throws Exception {
        String body = pay(merchant, amount, cardToken)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        JsonNode payment = json.readTree(body);
        return payment.get("id").asLong();
    }

    private ResultActions pay(long merchant, long amount, String cardToken) throws Exception {
        return mvc.perform(post("/v1/payments").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("""
                        {"merchant_wallet_id": %d, "amount": %d, "currency": "AED", "card_token": "%s"}
                        """.formatted(merchant, amount, cardToken)));
    }

    private ResultActions act(long payment, String action) throws Exception {
        return mvc.perform(post("/v1/payments/{id}/" + action, payment)
                .header("Idempotency-Key", UUID.randomUUID().toString()));
    }

    private static void expectInvalidState(ResultActions result, String message) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invalid_state"))
                .andExpect(jsonPath("$.message").value(message));
    }

    private long balance(AccountType systemAccount) {
        return ledger.balance(ledger.systemAccount(systemAccount, "AED").id()).amount();
    }
}
