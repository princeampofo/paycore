package com.paycore.mockprocessor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fake card processor, standing in for something like Stripe or Adyen. No dependencies, so it
 * runs straight from source: {@code java MockProcessor.java}.
 *
 *   POST /charges        {"ref": "...", "amount": 1000, "currency": "AED", "card_token": "tok_visa"}
 *                        → 200 {"ref": "...", "status": "approved" | "declined"}
 *   GET  /charges/{ref}  → 200 {"ref": "...", "status": ...}, or 404 if it never received that charge
 *
 * The caller chooses the ref. That matters: if the response to a charge is lost, the caller still
 * knows which ref to ask about. A repeated POST with the same ref returns the original result.
 *
 * Misbehaviour is configurable: latency, a decline rate, and a timeout rate. A "timeout" charges
 * the card and then sits on the response far longer than any client will wait for it.
 * The token "tok_declined" is always declined.
 */
public class MockProcessor {

    private static final long TIMEOUT_SLEEP_MS = 30_000;
    private static final Pattern JSON_FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*(?:\"([^\"]*)\"|(-?\\d+))");

    private final Map<String, String> chargeStatusByRef = new ConcurrentHashMap<>();
    private volatile long latencyMs;
    private volatile double declineRate;
    private volatile double timeoutRate;
    private HttpServer server;

    public MockProcessor(long latencyMs, double declineRate, double timeoutRate) {
        this.latencyMs = latencyMs;
        this.declineRate = declineRate;
        this.timeoutRate = timeoutRate;
    }

    public static void main(String[] args) throws IOException {
        MockProcessor processor = new MockProcessor(
                Long.parseLong(env("LATENCY_MS", "200")),
                Double.parseDouble(env("DECLINE_RATE", "0.1")),
                Double.parseDouble(env("TIMEOUT_RATE", "0.05")));
        int port = processor.start(Integer.parseInt(env("PORT", "8081")));
        System.out.println("Mock processor listening on port " + port);
    }

    /** Starts the server (port 0 picks a free one) and returns the port it listens on. */
    public int start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        // One virtual thread per request, so slow and timed-out charges don't hold up the rest.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/charges", this::handle);
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public void setDeclineRate(double declineRate) {
        this.declineRate = declineRate;
    }

    public void setTimeoutRate(double timeoutRate) {
        this.timeoutRate = timeoutRate;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (exchange.getRequestMethod().equals("POST") && path.equals("/charges")) {
                charge(exchange);
            } else if (exchange.getRequestMethod().equals("GET") && path.startsWith("/charges/")) {
                String ref = path.substring("/charges/".length());
                String status = chargeStatusByRef.get(ref);
                if (status == null) {
                    respond(exchange, 404, "{\"error\": \"no such charge\"}");
                } else {
                    respond(exchange, 200, chargeJson(ref, status));
                }
            } else {
                respond(exchange, 404, "{\"error\": \"not found\"}");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void charge(HttpExchange exchange) throws IOException, InterruptedException {
        Map<String, String> body = parseFlatJson(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String ref = body.get("ref");
        if (ref == null || body.get("amount") == null) {
            respond(exchange, 400, "{\"error\": \"ref and amount are required\"}");
            return;
        }

        Thread.sleep(latencyMs);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String status = chargeStatusByRef.computeIfAbsent(ref, r ->
                "tok_declined".equals(body.get("card_token")) || random.nextDouble() < declineRate
                        ? "declined" : "approved");

        if (random.nextDouble() < timeoutRate) {
            // The charge is recorded above, but the caller gives up long before this answer arrives.
            Thread.sleep(TIMEOUT_SLEEP_MS);
        }
        respond(exchange, 200, chargeJson(ref, status));
    }

    private static String chargeJson(String ref, String status) {
        return "{\"ref\": \"" + ref + "\", \"status\": \"" + status + "\"}";
    }

    /** Just enough JSON for flat objects of strings and integers, to keep this file dependency-free. */
    private static Map<String, String> parseFlatJson(String json) {
        Map<String, String> fields = new HashMap<>();
        Matcher matcher = JSON_FIELD.matcher(json);
        while (matcher.find()) {
            fields.put(matcher.group(1), matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
        }
        return fields;
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
