package com.paycore.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The outbox table: webhook events written alongside the change they describe, then delivered later.
 *
 * Why not just send the webhook right after committing? Because the process can die between the
 * commit and the send, and the event is lost. Or the send happens and then the commit fails, and
 * the merchant is told about a payment that never happened. Writing the event as a row in the same
 * transaction makes "the change happened" and "the event exists" the same fact.
 */
@Repository
public class Outbox {

    /** A claimed event, with where to send it. */
    public record PendingEvent(long id, String eventId, String payload, int attempts, String url, String secret) {
    }

    private static final RowMapper<PendingEvent> PENDING_EVENT = (rs, row) -> new PendingEvent(
            rs.getLong("id"), rs.getString("event_id"), rs.getString("payload"), rs.getInt("attempts"),
            rs.getString("url"), rs.getString("secret"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public Outbox(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Records an event for the merchant's webhook endpoint, if it has one. MANDATORY: this must
     * join the caller's transaction, because the whole point is to commit with the change itself.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(long merchantWalletId, String type, Object data) {
        String eventId = "evt_" + UUID.randomUUID();
        String payload = toJson(new WebhookEvent(eventId, type, Instant.now(), data));
        jdbc.update("""
                INSERT INTO outbox (event_id, endpoint_id, type, payload)
                SELECT ?, id, ?, ? FROM webhook_endpoints WHERE merchant_wallet_id = ?""",
                eventId, type, payload, merchantWalletId);
    }

    /**
     * Claims up to {@code limit} due events and returns them. One statement, committed straight away:
     *
     * - FOR UPDATE SKIP LOCKED: if another dispatcher is claiming some rows at this very moment,
     *   skip them instead of waiting, so dispatchers never block each other or claim the same row.
     * - Pushing next_attempt_at forward by {@code lease}: once committed, these rows aren't due for
     *   anyone else while we send them. If we crash mid-send, the lease runs out and they're sent again.
     *
     * No transaction stays open during the HTTP calls; a slow merchant can't hold locks or connections.
     */
    public List<PendingEvent> claimDue(int limit, Duration lease) {
        return jdbc.query("""
                WITH claimed AS (
                    UPDATE outbox
                    SET attempts = attempts + 1, next_attempt_at = now() + make_interval(secs => ?)
                    WHERE id IN (
                        SELECT id FROM outbox
                        WHERE status = 'pending' AND next_attempt_at <= now()
                        ORDER BY id
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED)
                    RETURNING *)
                SELECT c.id, c.event_id, c.payload, c.attempts, w.url, w.secret
                FROM claimed c JOIN webhook_endpoints w ON w.id = c.endpoint_id
                ORDER BY c.id""",
                PENDING_EVENT, seconds(lease), limit);
    }

    public void markDelivered(long id) {
        jdbc.update("UPDATE outbox SET status = 'delivered', delivered_at = now(), last_error = NULL WHERE id = ?", id);
    }

    public void retryLater(long id, Duration delay, String error) {
        jdbc.update("UPDATE outbox SET next_attempt_at = now() + make_interval(secs => ?), last_error = ? WHERE id = ?",
                seconds(delay), error, id);
    }

    /** Gave up: kept for inspection (and a manual replay) instead of retrying forever. */
    public void markDead(long id, String error) {
        jdbc.update("UPDATE outbox SET status = 'dead', last_error = ? WHERE id = ?", error, id);
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + value, e);
        }
    }
}
