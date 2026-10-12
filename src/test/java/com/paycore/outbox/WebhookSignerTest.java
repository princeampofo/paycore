package com.paycore.outbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignerTest {

    private static final String SECRET = "whsec_test";
    private static final long NOW = 1_760_300_000L;
    private static final String BODY = "{\"id\":\"evt_1\"}";

    @Test
    void matchesAnIndependentlyComputedHmac() {
        // python3: hmac.new(b'whsec_test', b'1760300000.{"id":"evt_1"}', hashlib.sha256).hexdigest()
        assertThat(WebhookSigner.signatureHeader(SECRET, NOW, BODY))
                .isEqualTo("t=1760300000,v1=550dddd9652d3cdcaa1080b455f6af0a4d0510424fdbadbcf5d63dcaa356e2b8");
    }

    @Test
    void aGenuineSignatureVerifies() {
        String header = WebhookSigner.signatureHeader(SECRET, NOW, BODY);

        assertThat(WebhookSigner.verify(SECRET, header, BODY, NOW, 300)).isTrue();
    }

    @Test
    void aChangedBodyIsRejected() {
        String header = WebhookSigner.signatureHeader(SECRET, NOW, BODY);

        assertThat(WebhookSigner.verify(SECRET, header, "{\"id\":\"evt_2\"}", NOW, 300)).isFalse();
    }

    @Test
    void theWrongSecretIsRejected() {
        String header = WebhookSigner.signatureHeader("whsec_attacker", NOW, BODY);

        assertThat(WebhookSigner.verify(SECRET, header, BODY, NOW, 300)).isFalse();
    }

    @Test
    void anOldWebhookReplayedLaterIsRejected() {
        String header = WebhookSigner.signatureHeader(SECRET, NOW, BODY);

        assertThat(WebhookSigner.verify(SECRET, header, BODY, NOW + 301, 300)).isFalse();
    }

    @Test
    void aMalformedHeaderIsRejected() {
        assertThat(WebhookSigner.verify(SECRET, null, BODY, NOW, 300)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, "v1=abc", BODY, NOW, 300)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, "t=1760300000,v1=nothex", BODY, NOW, 300)).isFalse();
    }
}
