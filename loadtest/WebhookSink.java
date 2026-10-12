import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A merchant's webhook endpoint for load tests: answers 200 to every webhook and prints how many
 * it has received. No dependencies, so it runs straight from source: {@code java WebhookSink.java}.
 */
public class WebhookSink {

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "9000"));
        AtomicLong received = new AtomicLong();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/webhooks", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                received.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.start();
        System.out.println("Webhook sink listening on port " + port);

        long last = 0;
        while (true) {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                return;
            }
            long now = received.get();
            if (now != last) {
                System.out.println("webhooks received: " + now);
                last = now;
            }
        }
    }
}
