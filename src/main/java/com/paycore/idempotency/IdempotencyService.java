package com.paycore.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.api.ApiException;
import com.paycore.ledger.InsufficientFundsException;
import com.paycore.metrics.PaycoreMetrics;
import com.paycore.payments.IllegalTransitionException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Makes a POST safe to retry: the first request with a given Idempotency-Key does the work,
 * and every later request with the same key gets the first one's response back.
 *
 * It happens in two steps:
 * 1. Claim the key: insert it as 'in_progress' and commit straight away, so concurrent
 *    duplicates can see that someone is already working on it (and get a 409).
 * 2. Do the work and save the response as 'completed' in ONE transaction. So a completed key
 *    always means the work happened.
 */
@Service
public class IdempotencyService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final PaycoreMetrics metrics;

    public IdempotencyService(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper json,
                              PaycoreMetrics metrics) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.json = json;
        this.metrics = metrics;
    }

    /**
     * Runs {@code action} at most once per key, in one transaction with saving its response.
     * Must not be called inside a transaction: the claim has to commit on its own for concurrent
     * duplicates to see it.
     */
    public SavedResponse execute(String key, String endpoint, Object request, HttpStatus successStatus,
                                 Supplier<?> action) {
        return execute(key, endpoint, request, successStatus, () -> null, ignored -> action.get());
    }

    /**
     * For work that must call another system before writing its result, like charging a card.
     * {@code outsideTransaction} runs once, after the key is claimed, with no transaction open (it may
     * commit its own writes). {@code inTransaction} then gets its result and runs in one transaction
     * with saving the response.
     */
    public <T> SavedResponse execute(String key, String endpoint, Object request, HttpStatus successStatus,
                                     Supplier<T> outsideTransaction, Function<T, ?> inTransaction) {
        if (key.isBlank() || key.length() > 255) {
            throw ApiException.badRequest("invalid_idempotency_key", "Idempotency-Key must be 1 to 255 characters");
        }
        String requestHash = requestHash(endpoint, request);
        if (!claim(key, requestHash)) {
            return existingResponse(key, requestHash);
        }

        // Only a refusal frees the key. Any other error (database down, a connection lost during
        // commit, a bug) leaves it 'in_progress': we can't be sure the work didn't happen, so a retry
        // gets 409 rather than a second attempt that might move the money twice. It frees itself
        // when it expires.
        try {
            T prepared = outsideTransaction.get();
            return transaction.execute(tx -> {
                SavedResponse response = new SavedResponse(successStatus.value(), toJson(inTransaction.apply(prepared)));
                jdbc.update("""
                        UPDATE idempotency_keys SET status = 'completed', response_status = ?, response_body = ?
                        WHERE key = ?""", response.status(), response.body(), key);
                return response;
            });
        } catch (ApiException | InsufficientFundsException | IllegalTransitionException e) {
            // The request was refused and its transaction rolled back, so nothing happened:
            // free the key so the client can fix the problem and retry.
            jdbc.update("DELETE FROM idempotency_keys WHERE key = ? AND status = 'in_progress'", key);
            throw e;
        }
    }

    /** SHA-256 of the endpoint and the request as JSON. Same request, same hash. */
    public String requestHash(String endpoint, Object request) {
        try {
            byte[] bytes = (endpoint + "\n" + toJson(request)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }

    /**
     * Returns true if this request now owns the key. Of many concurrent requests with the same
     * key, exactly one INSERT wins the primary key; the others hit the conflict and get false.
     * A key older than 24 hours has expired and is taken over as if it were new.
     */
    private boolean claim(String key, String requestHash) {
        List<String> claimed = jdbc.queryForList("""
                INSERT INTO idempotency_keys (key, request_hash, status) VALUES (?, ?, 'in_progress')
                ON CONFLICT (key) DO UPDATE
                    SET request_hash = EXCLUDED.request_hash, status = 'in_progress',
                        response_status = NULL, response_body = NULL, created_at = now()
                    WHERE idempotency_keys.created_at < now() - interval '24 hours'
                RETURNING key""", String.class, key, requestHash);
        return !claimed.isEmpty();
    }

    private record KeyRow(String requestHash, String status, Integer responseStatus, String responseBody) {
    }

    private SavedResponse existingResponse(String key, String requestHash) {
        List<KeyRow> rows = jdbc.query(
                "SELECT request_hash, status, response_status, response_body FROM idempotency_keys WHERE key = ?",
                (rs, n) -> new KeyRow(rs.getString("request_hash"), rs.getString("status"),
                        rs.getObject("response_status", Integer.class), rs.getString("response_body")),
                key);

        // No row: the first request failed and released the key a moment ago. A retry will claim it.
        if (rows.isEmpty()) {
            throw inProgress();
        }
        KeyRow row = rows.getFirst();
        if (!row.requestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency_key_reused",
                    "This Idempotency-Key was already used for a different request");
        }
        if (row.status().equals("in_progress")) {
            throw inProgress();
        }
        metrics.idempotentReplay();
        return new SavedResponse(row.responseStatus(), row.responseBody());
    }

    private static ApiException inProgress() {
        return new ApiException(HttpStatus.CONFLICT, "request_in_progress",
                "A request with this Idempotency-Key is still being processed; retry later");
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + value, e);
        }
    }
}
