package com.paycore.api;

import com.paycore.TestcontainersConfiguration;
import com.paycore.ledger.Ledger;
import com.paycore.payments.CreatePaymentRequest;
import com.paycore.payments.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static com.paycore.TestWallets.fundedWallet;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The read side the wallet app uses: a wallet's history and listing payments. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WalletAndPaymentListTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Ledger ledger;
    @Autowired
    PaymentService paymentService;

    @Test
    void walletHistoryIsNewestFirstWithNotesAndTheOtherSide() throws Exception {
        long alice = fundedWallet(ledger, "alice", 10_000);
        long bob = fundedWallet(ledger, "bob", 0);
        transfer(alice, bob, 2_500, "lunch").andExpect(status().isCreated());

        mvc.perform(get("/v1/wallets/{id}/entries", bob))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].description").value("transfer"))
                .andExpect(jsonPath("$.data[0].note").value("lunch"))
                .andExpect(jsonPath("$.data[0].amount").value(2_500))
                .andExpect(jsonPath("$.data[0].postings[0].account_id").value(alice))
                .andExpect(jsonPath("$.data[0].postings[0].amount").value(-2_500));

        mvc.perform(get("/v1/wallets/{id}/entries", alice))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].amount").value(-2_500)) // the transfer
                .andExpect(jsonPath("$.data[1].description").value("top-up"));
    }

    @Test
    void walletHistoryPagesWithACursor() throws Exception {
        long alice = fundedWallet(ledger, "alice", 10_000);
        long bob = fundedWallet(ledger, "bob", 0);
        for (int i = 1; i <= 3; i++) {
            transfer(alice, bob, i * 100, null).andExpect(status().isCreated());
        }

        String firstPage = mvc.perform(get("/v1/wallets/{id}/entries?limit=2", bob))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].amount").value(300))
                .andReturn().getResponse().getContentAsString();
        long cursor = com.jayway.jsonpath.JsonPath.<Number>read(firstPage, "$.next_before").longValue();

        mvc.perform(get("/v1/wallets/{id}/entries?limit=2&before={cursor}", bob, cursor))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].amount").value(100))
                .andExpect(jsonPath("$.next_before").value(nullValue()));
    }

    @Test
    void notesAreOptionalButLimitedTo140Characters() throws Exception {
        long alice = fundedWallet(ledger, "alice", 10_000);
        long bob = fundedWallet(ledger, "bob", 0);

        transfer(alice, bob, 100, "x".repeat(141))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_note"));
    }

    @Test
    void paymentsCanBeListedByMerchantOrByCustomer() throws Exception {
        long cafe = fundedWallet(ledger, "corner-cafe", 0);
        String alice = "alice-" + UUID.randomUUID();
        paymentService.createPayment(new CreatePaymentRequest(cafe, 1_000, "AED", "tok_visa", alice, "manual"));
        paymentService.createPayment(new CreatePaymentRequest(cafe, 2_000, "AED", "tok_visa", "someone-else", "manual"));

        mvc.perform(get("/v1/payments?merchant_wallet_id={id}", cafe))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].amount").value(2_000)); // newest first
        mvc.perform(get("/v1/payments?customer={customer}", alice))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].customer").value(alice))
                .andExpect(jsonPath("$.data[0].status").value("authorized"));
        mvc.perform(get("/v1/payments"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_filter"));
    }

    private ResultActions transfer(long from, long to, long amount, String note) throws Exception {
        String noteJson = note == null ? "" : ", \"note\": \"" + note + "\"";
        return mvc.perform(post("/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"from_wallet_id\": %d, \"to_wallet_id\": %d, \"amount\": %d, \"currency\": \"AED\"%s}"
                        .formatted(from, to, amount, noteJson)));
    }
}
