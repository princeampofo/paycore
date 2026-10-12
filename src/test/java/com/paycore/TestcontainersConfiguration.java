package com.paycore;

import com.paycore.mockprocessor.MockProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;

/**
 * A real Postgres 16 and a mock processor for tests. Spring caches the context, so test classes
 * share them. The ledger is append-only, so tests create fresh wallets instead of wiping tables.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }

    /** Approves everything instantly unless a test changes its settings (and resets them afterwards). */
    @Bean(destroyMethod = "stop")
    MockProcessor mockProcessor() throws IOException {
        MockProcessor processor = new MockProcessor(0, 0, 0);
        processor.start(0);
        return processor;
    }

    @Bean
    DynamicPropertyRegistrar processorProperties(MockProcessor processor) {
        return registry -> {
            registry.add("paycore.processor.base-url", () -> "http://localhost:" + processor.port());
            registry.add("paycore.processor.timeout", () -> "1s");
        };
    }
}
