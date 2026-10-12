package com.paycore.idempotency;

import com.paycore.TestcontainersConfiguration;
import com.paycore.ledger.Ledger;
import com.paycore.ledger.Money;
import com.paycore.transfers.TransferRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static com.paycore.TestWallets.fundedWallet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class IdempotencyTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Ledger ledger;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    IdempotencyService idempotency;

    @Test
    void retryWithTheSameKeyReplaysTheResponseWithoutMovingMoneyAgain() throws Exception {
        long alice = fundedWallet(ledger, "alice", 10_000);
        long bob = fundedWallet(ledger, "bob", 0);
        String key = newKey();

        String first = transfer(key, alice, bob, 1_000).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString();
        String retry = transfer(key, alice, bob, 1_000).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString();

        assertThat(retry).isEqualTo(first);
        assertThat(ledger.balance(alice)).isEqualTo(Money.of(9_000, "AED"));
        assertThat(ledger.balance(bob)).isEqualTo(Money.of(1_000, "AED"));
    }

    @Test
    void sameKeyForADifferentRequestIsRejected() throws Exception {
        long alice = fundedWallet(ledger, "alice", 10_000);
        long bob = fundedWallet(ledger, "bob", 0);
        String key = newKey();

        transfer(key, alice, bob, 1_000).andExpect(status().isCreated());
        transfer(key, alice, bob, 2_000)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("idempotency_key_reused"));

        assertThat(ledger.balance(bob)).isEqualTo(Money.of(1_000, "AED"));
    }

    @Test
    void sameKeyWhileTheFirstRequestIsStillRunningIs409() throws Exception {
        long alice = fundedWallet(ledger, "alice", 10_000);
        long bob = fundedWallet(ledger, "bob", 0);
        String key = newKey();
        // Pretend another request has claimed this key and hasn't finished yet.
        String hash = idempotency.requestHash("POST /v1/transfers", new TransferRequest(alice, bob, 1_000, "AED"));
        jdbc.update("INSERT INTO idempotency_keys (key, request_hash, status) VALUES (?, ?, 'in_progress')", key, hash);

        transfer(key, alice, bob, 1_000)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("request_in_progress"));

        assertThat(ledger.balance(bob)).isEqualTo(Money.of(0, "AED"));
    }

    @Test
    void aFailedRequestFreesTheKeySoARetryCanSucceed() throws Exception {
        long alice = fundedWallet(ledger, "alice", 500);
        long bob = fundedWallet(ledger, "bob", 0);
        String key = newKey();

        transfer(key, alice, bob, 1_000).andExpect(jsonPath("$.code").value("insufficient_funds"));
        assertThat(keyExists(key)).isFalse();

        transfer(key, alice, bob, 500).andExpect(status().isCreated());
        assertThat(ledger.balance(bob)).isEqualTo(Money.of(500, "AED"));
    }

    @Test
    void keysExpireAfter24Hours() throws Exception {
        long alice = fundedWallet(ledger, "alice", 10_000);
        long bob = fundedWallet(ledger, "bob", 0);
        String key = newKey();
        transfer(key, alice, bob, 1_000).andExpect(status().isCreated());

        jdbc.update("UPDATE idempotency_keys SET created_at = now() - interval '25 hours' WHERE key = ?", key);

        // A different request with the old key is now simply a new request.
        transfer(key, alice, bob, 2_000).andExpect(status().isCreated());
        assertThat(ledger.balance(bob)).isEqualTo(Money.of(3_000, "AED"));
    }

    @Test
    void requestsWithoutAKeyAreRejected() throws Exception {
        long alice = fundedWallet(ledger, "alice", 10_000);
        long bob = fundedWallet(ledger, "bob", 0);

        mvc.perform(post("/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(body(alice, bob, 1_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("missing_header"));

        assertThat(ledger.balance(bob)).isEqualTo(Money.of(0, "AED"));
    }

    private ResultActions transfer(String key, long from, long to, long amount) throws Exception {
        return mvc.perform(post("/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content(body(from, to, amount)));
    }

    private static String body(long from, long to, long amount) {
        return """
                {"from_wallet_id": %d, "to_wallet_id": %d, "amount": %d, "currency": "AED"}
                """.formatted(from, to, amount);
    }

    private boolean keyExists(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE key = ?", Long.class, key) > 0;
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }
}
