package com.paycore.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.outbox.WebhookSigner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Endpoints that only exist with the "demo" profile. Never in the default profile: they expose
 * every user, and the webhook receiver below stands in for a merchant's own server.
 */
@RestController
@Profile("demo")
public class DemoController {

    /** One webhook the café's endpoint received. */
    public record ReceivedWebhook(String eventId, String type, long paymentId, Instant receivedAt,
                                  boolean signatureValid) {
    }

    private static final int WEBHOOKS_KEPT = 50;

    private final DemoUsers users;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Deque<ReceivedWebhook> received = new ConcurrentLinkedDeque<>();

    public DemoController(DemoUsers users, JdbcTemplate jdbc, ObjectMapper json) {
        this.users = users;
        this.jdbc = jdbc;
        this.json = json;
    }

    @GetMapping("/demo/users")
    public List<DemoUsers.User> users() {
        return users.users();
    }

    /**
     * The Corner Café's webhook endpoint, written the way a merchant should write theirs: check the
     * signature, then ignore event ids already seen (delivery is at-least-once).
     */
    @PostMapping("/demo/webhooks/receive")
    public ResponseEntity<Void> receive(@RequestHeader(WebhookSigner.HEADER) String signature,
                                        @RequestBody String body) throws IOException {
        String secret = jdbc.queryForObject("SELECT secret FROM webhook_endpoints WHERE merchant_wallet_id = ?",
                String.class, users.merchant().walletId());
        boolean valid = WebhookSigner.verify(secret, signature, body, Instant.now().getEpochSecond(), 300);
        JsonNode event = json.readTree(body);
        String eventId = event.path("id").asText();
        if (received.stream().noneMatch(webhook -> webhook.eventId().equals(eventId))) {
            received.addFirst(new ReceivedWebhook(eventId, event.path("type").asText(),
                    event.path("data").path("id").asLong(), Instant.now(), valid));
            while (received.size() > WEBHOOKS_KEPT) {
                received.removeLast();
            }
        }
        return valid ? ResponseEntity.ok().build() : ResponseEntity.badRequest().build();
    }

    /** What the café's endpoint has received, newest first. */
    @GetMapping("/demo/webhooks")
    public List<ReceivedWebhook> webhooks() {
        return new ArrayList<>(received);
    }
}
