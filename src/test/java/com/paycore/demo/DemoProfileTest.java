package com.paycore.demo;

import com.paycore.TestcontainersConfiguration;
import com.paycore.ledger.Ledger;
import com.paycore.ledger.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Import(TestcontainersConfiguration.class)
class DemoProfileTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    DemoUsers demoUsers;
    @Autowired
    Ledger ledger;

    @Test
    void seedsThreeCustomersAndAMerchantWithStartingBalances() throws Exception {
        mvc.perform(get("/demo/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Alice"))
                .andExpect(jsonPath("$[3].name").value("Corner Café"))
                .andExpect(jsonPath("$[3].role").value("MERCHANT"));

        DemoUsers.User alice = demoUsers.users().getFirst();
        assertThat(ledger.balance(alice.walletId())).isEqualTo(Money.of(50_000, "AED"));
    }

    @Test
    void seedingTwiceDoesNothing() throws Exception {
        demoUsers.run(null);

        assertThat(demoUsers.users()).hasSize(4);
    }

    @Test
    void servesTheWalletApp() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("paycore wallet")));
    }
}
