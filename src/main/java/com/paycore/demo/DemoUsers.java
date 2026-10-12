package com.paycore.demo;

import com.paycore.ledger.AccountType;
import com.paycore.ledger.JournalEntry;
import com.paycore.ledger.Ledger;
import com.paycore.ledger.Money;
import com.paycore.ledger.Posting;
import com.paycore.outbox.WebhookEndpointService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The demo's people: three customers and one merchant, created with starting balances the first
 * time the app starts with the "demo" profile. There's no sign-up or login; the demo app has a
 * "View as" switcher instead.
 */
@Component
@Profile("demo")
public class DemoUsers implements ApplicationRunner {

    public enum Role { CUSTOMER, MERCHANT }

    /** {@code key} is what the demo app uses to identify the user (and the customer label on payments). */
    public record User(String key, String name, Role role, long walletId) {
    }

    private record Seed(String key, String name, Role role, long startingBalance) {
    }

    private static final String CURRENCY = "AED";
    private static final List<Seed> SEEDS = List.of(
            new Seed("alice", "Alice", Role.CUSTOMER, 50_000),     // 500.00 AED
            new Seed("bob", "Bob", Role.CUSTOMER, 25_000),         // 250.00 AED
            new Seed("fatima", "Fatima", Role.CUSTOMER, 100_000),  // 1,000.00 AED
            new Seed("cafe", "Corner Café", Role.MERCHANT, 0));

    private static final Logger log = LoggerFactory.getLogger(DemoUsers.class);

    private final Ledger ledger;
    private final JdbcTemplate jdbc;
    private final WebhookEndpointService webhookEndpoints;
    private final String webhookUrl;

    public DemoUsers(Ledger ledger, JdbcTemplate jdbc, WebhookEndpointService webhookEndpoints,
                     @Value("${paycore.demo.webhook-url}") String webhookUrl) {
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.webhookEndpoints = webhookEndpoints;
        this.webhookUrl = webhookUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!users().isEmpty()) {
            return;
        }
        long funding = ledger.systemAccount(AccountType.EXTERNAL_FUNDING, CURRENCY).id();
        for (Seed seed : SEEDS) {
            long wallet = ledger.openWallet(CURRENCY, seed.name()).id();
            if (seed.startingBalance() > 0) {
                Money amount = Money.of(seed.startingBalance(), CURRENCY);
                ledger.post(JournalEntry.of("top-up",
                        new Posting(funding, amount.negate()),
                        new Posting(wallet, amount)));
            }
            if (seed.role() == Role.MERCHANT) {
                // The café's webhooks come back to this app, so the demo can show what it received.
                webhookEndpoints.register(new WebhookEndpointService.RegisterRequest(wallet, webhookUrl));
            }
        }
        log.info("Demo users created: {}", users());
    }

    public List<User> users() {
        return SEEDS.stream()
                .flatMap(seed -> jdbc.queryForList(
                                "SELECT id FROM accounts WHERE type = 'WALLET' AND owner = ? ORDER BY id LIMIT 1",
                                Long.class, seed.name()).stream()
                        .map(walletId -> new User(seed.key(), seed.name(), seed.role(), walletId)))
                .toList();
    }

    public User merchant() {
        return users().stream().filter(user -> user.role() == Role.MERCHANT).findFirst().orElseThrow();
    }
}
