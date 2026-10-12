package com.paycore.demo;

import com.paycore.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A deliberate safety choice: without the "demo" profile, none of the demo surface exists. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NoDemoEndpointsByDefaultTest {

    @Autowired
    MockMvc mvc;

    @Test
    void demoEndpointsAndTheWalletAppDoNotExist() throws Exception {
        mvc.perform(get("/demo/users")).andExpect(status().isNotFound());
        mvc.perform(post("/demo/webhooks/receive").header("Paycore-Signature", "x").content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/index.html")).andExpect(status().isNotFound());
    }
}
