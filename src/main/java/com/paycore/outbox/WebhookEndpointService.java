package com.paycore.outbox;

import com.paycore.api.ApiException;
import com.paycore.ledger.Ledger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.SecureRandom;
import java.util.HexFormat;

@Service
public class WebhookEndpointService {

    public record RegisterRequest(long merchantWalletId, String url) {
    }

    /** {@code secret} is what the merchant uses to verify our signatures. */
    public record EndpointResponse(long id, long merchantWalletId, String url, String secret) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final Ledger ledger;

    public WebhookEndpointService(JdbcTemplate jdbc, Ledger ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    /** Sets the merchant's endpoint, replacing (and re-keying) any previous one. */
    @Transactional
    public EndpointResponse register(RegisterRequest request) {
        ledger.findWallet(request.merchantWalletId())
                .orElseThrow(() -> ApiException.notFound("wallet_not_found",
                        "No merchant wallet with id " + request.merchantWalletId()));
        if (!isHttpUrl(request.url())) {
            throw ApiException.badRequest("invalid_url", "url must be an absolute http or https URL");
        }

        byte[] key = new byte[24];
        RANDOM.nextBytes(key);
        String secret = "whsec_" + HexFormat.of().formatHex(key);
        long id = jdbc.queryForObject("""
                INSERT INTO webhook_endpoints (merchant_wallet_id, url, secret) VALUES (?, ?, ?)
                ON CONFLICT (merchant_wallet_id) DO UPDATE SET url = EXCLUDED.url, secret = EXCLUDED.secret
                RETURNING id""", Long.class, request.merchantWalletId(), request.url(), secret);
        return new EndpointResponse(id, request.merchantWalletId(), request.url(), secret);
    }

    private static boolean isHttpUrl(String url) {
        try {
            URI uri = URI.create(url);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }
}
