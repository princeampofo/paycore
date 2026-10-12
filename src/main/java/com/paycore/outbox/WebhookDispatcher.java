package com.paycore.outbox;

import com.paycore.outbox.Outbox.PendingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;

/**
 * Delivers outbox events to merchants' webhook endpoints, retrying with exponential backoff.
 *
 * Delivery is at-least-once: if we crash after the merchant got an event but before we mark it
 * delivered, it's sent again. Exactly-once delivery over a network isn't possible (we can never
 * be sure the other side got it), so the honest guarantee is "at least once, with an event id",
 * and the receiver gets exactly-once *effect* by ignoring event ids it has already processed.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final int BATCH_SIZE = 50;
    private static final Duration MAX_DELAY = Duration.ofHours(1);

    private final Outbox outbox;
    private final RestClient http;
    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration lease;

    public WebhookDispatcher(Outbox outbox, RestClient.Builder builder,
                             @Value("${paycore.webhooks.timeout}") Duration timeout,
                             @Value("${paycore.webhooks.max-attempts}") int maxAttempts,
                             @Value("${paycore.webhooks.base-delay}") Duration baseDelay,
                             @Value("${paycore.webhooks.lease}") Duration lease) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        this.outbox = outbox;
        this.http = builder.requestFactory(requestFactory).build();
        this.maxAttempts = maxAttempts;
        this.baseDelay = baseDelay;
        this.lease = lease;
    }

    @Scheduled(fixedDelayString = "${paycore.webhooks.interval-ms}", initialDelayString = "${paycore.webhooks.interval-ms}")
    void runOnSchedule() {
        // Keep going while batches come back full, so a backlog drains at full speed rather than
        // one batch per tick. It stops at the first short batch (nothing left, or some sends failed).
        while (dispatchDue() == BATCH_SIZE) {
            log.debug("Outbox backlog: sending the next batch");
        }
    }

    /** Sends one batch of due events. Returns how many were delivered. */
    public int dispatchDue() {
        int delivered = 0;
        for (PendingEvent event : outbox.claimDue(BATCH_SIZE, lease)) {
            String error = send(event);
            if (error == null) {
                outbox.markDelivered(event.id());
                delivered++;
            } else if (event.attempts() >= maxAttempts) {
                log.warn("Webhook {} is dead after {} attempts: {}", event.eventId(), event.attempts(), error);
                outbox.markDead(event.id(), error);
            } else {
                outbox.retryLater(event.id(), backoff(baseDelay, event.attempts()), error);
            }
        }
        return delivered;
    }

    /**
     * Wait base × 2^(attempts − 1) before the next try (e.g. 2 s, 4 s, 8 s, ...), capped at an hour.
     * Backing off gives a struggling receiver room to recover instead of hammering it.
     */
    public static Duration backoff(Duration baseDelay, int attempts) {
        Duration delay = baseDelay.multipliedBy(1L << Math.min(attempts - 1, 20));
        return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
    }

    /** Returns null on a 2xx, otherwise what went wrong. */
    private String send(PendingEvent event) {
        try {
            http.post().uri(event.url())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Paycore-Event-Id", event.eventId())
                    .header(WebhookSigner.HEADER,
                            WebhookSigner.signatureHeader(event.secret(), Instant.now().getEpochSecond(), event.payload()))
                    .body(event.payload())
                    .retrieve()
                    .toBodilessEntity();
            return null;
        } catch (RestClientException | IllegalArgumentException e) {
            return e.getMessage();
        }
    }
}
