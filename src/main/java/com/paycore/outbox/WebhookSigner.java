package com.paycore.outbox;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Signs webhooks so a merchant can check they came from us and weren't changed on the way.
 *
 * Header: {@code Paycore-Signature: t=1760300000,v1=<hex HMAC-SHA256(secret, "1760300000." + body)>}
 *
 * The timestamp is part of what's signed, so an attacker who captured an old webhook can't
 * replay it later: the receiver rejects signatures older than a few minutes.
 */
public final class WebhookSigner {

    public static final String HEADER = "Paycore-Signature";

    private WebhookSigner() {
    }

    public static String signatureHeader(String secret, long timestampSeconds, String body) {
        return "t=" + timestampSeconds + ",v1=" + hmac(secret, timestampSeconds + "." + body);
    }

    /** What a receiver does: recompute the signature and compare, and refuse anything too old. */
    public static boolean verify(String secret, String header, String body, long nowSeconds, long toleranceSeconds) {
        if (header == null || !header.matches("t=\\d+,v1=[0-9a-f]{64}")) {
            return false;
        }
        String[] parts = header.split(",");
        long timestamp = Long.parseLong(parts[0].substring(2));
        if (Math.abs(nowSeconds - timestamp) > toleranceSeconds) {
            return false;
        }
        byte[] expected = hmac(secret, timestamp + "." + body).getBytes(StandardCharsets.UTF_8);
        byte[] actual = parts[1].substring(3).getBytes(StandardCharsets.UTF_8);
        // Constant-time comparison: an early-exit equals() would leak how many characters matched.
        return MessageDigest.isEqual(expected, actual);
    }

    private static String hmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is always available in the JDK", e);
        }
    }
}
