package com.paycore.api;

import com.paycore.TestcontainersConfiguration;
import com.paycore.ledger.AccountType;
import com.paycore.ledger.Ledger;
import com.paycore.ledger.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.paycore.TestWallets.fundedWallet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TransferApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Ledger ledger;

    @Test
    void transfersMoneyBetweenWallets() throws Exception {
        long alice = fundedWallet(ledger, "alice", 10_000);
        long bob = fundedWallet(ledger, "bob", 0);

        transfer(alice, bob, 5_000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.from_wallet_id").value(alice))
                .andExpect(jsonPath("$.to_wallet_id").value(bob))
                .andExpect(jsonPath("$.amount").value(5_000))
                .andExpect(jsonPath("$.currency").value("AED"));

        mvc.perform(get("/v1/wallets/{id}/balance", bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(5_000));
        assertThat(ledger.balance(alice)).isEqualTo(Money.of(5_000, "AED"));
    }

    @Test
    void insufficientFundsIsAClean422() throws Exception {
        long alice = fundedWallet(ledger, "alice", 3_000);
        long bob = fundedWallet(ledger, "bob", 0);

        transfer(alice, bob, 5_000)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.code").value("insufficient_funds"))
                .andExpect(jsonPath("$.message").value("Wallet " + alice + " has insufficient funds"));
        assertThat(ledger.balance(alice)).isEqualTo(Money.of(3_000, "AED"));
    }

    @Test
    void unknownWalletIs404() throws Exception {
        long alice = fundedWallet(ledger, "alice", 3_000);

        transfer(alice, 999_999_999, 100)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("wallet_not_found"));
    }

    @Test
    void systemAccountsCannotBeUsedAsWallets() throws Exception {
        long funding = ledger.systemAccount(AccountType.EXTERNAL_FUNDING, "AED").id();
        long alice = fundedWallet(ledger, "alice", 0);

        // external_funding may go negative, so this would otherwise mint money.
        transfer(funding, alice, 1_000_000)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("wallet_not_found"));
    }

    @Test
    void rejectsNonPositiveAmountsAndSelfTransfers() throws Exception {
        long alice = fundedWallet(ledger, "alice", 3_000);
        long bob = fundedWallet(ledger, "bob", 0);

        transfer(alice, bob, 0).andExpect(jsonPath("$.code").value("invalid_amount"));
        transfer(alice, bob, -100).andExpect(jsonPath("$.code").value("invalid_amount"));
        transfer(alice, alice, 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("same_wallet"));
    }

    @Test
    void missingFieldsAreRejected() throws Exception {
        mvc.perform(post("/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from_wallet_id\": 1, \"to_wallet_id\": 2, \"currency\": \"AED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_body"));
    }

    private ResultActions transfer(long from, long to, long amount) throws Exception {
        return mvc.perform(post("/v1/transfers").contentType(MediaType.APPLICATION_JSON).content("""
                {"from_wallet_id": %d, "to_wallet_id": %d, "amount": %d, "currency": "AED"}
                """.formatted(from, to, amount)));
    }
}
