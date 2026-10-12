package com.paycore.outbox;

import java.time.Instant;

/** The JSON body of a webhook, e.g. {"id": "evt_...", "type": "payment.captured", "created_at": ..., "data": {...}}. */
public record WebhookEvent(String id, String type, Instant createdAt, Object data) {
}
