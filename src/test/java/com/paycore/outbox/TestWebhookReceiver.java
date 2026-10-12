package com.paycore.outbox;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A merchant's webhook endpoint, written the way we tell merchants to write theirs: verify the
 * signature, and process each event id only once.
 */
class TestWebhookReceiver implements AutoCloseable {

    enum Mode {
        /** Accepts webhooks. */
        UP,
        /** Answers 503 without looking at anything: the merchant's server is down. */
        DOWN,
        /** Processes the webhook but then answers 500, so we'll send it again. */
        PROCESS_THEN_FAIL
    }

    record Delivery(String eventId, String body, boolean signatureValid) {
    }

    private final HttpServer server;
    private volatile Mode mode = Mode.UP;
    private volatile String secret;
    /** Every request that got past "down", duplicates included. */
    final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
    /** The receiver's dedupe table: event ids it has acted on. */
    final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();

    TestWebhookReceiver() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/webhooks", this::handle);
        server.start();
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/webhooks";
    }

    void setSecret(String secret) {
        this.secret = secret;
    }

    void setMode(Mode mode) {
        this.mode = mode;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (mode == Mode.DOWN) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String eventId = exchange.getRequestHeaders().getFirst("Paycore-Event-Id");
            boolean valid = WebhookSigner.verify(secret, exchange.getRequestHeaders().getFirst(WebhookSigner.HEADER),
                    body, Instant.now().getEpochSecond(), 300);
            deliveries.add(new Delivery(eventId, body, valid));
            if (!valid) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            processedEventIds.add(eventId); // a duplicate is simply already in the set
            exchange.sendResponseHeaders(mode == Mode.PROCESS_THEN_FAIL ? 500 : 200, -1);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
