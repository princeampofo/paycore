-- Where a merchant wants to be told about its payments. One endpoint per merchant.
CREATE TABLE webhook_endpoints (
    id                 BIGSERIAL   PRIMARY KEY,
    merchant_wallet_id BIGINT      NOT NULL UNIQUE REFERENCES accounts (id),
    url                TEXT        NOT NULL,
    -- Shared secret for the HMAC signature, so the merchant can check a webhook really came from us.
    secret             TEXT        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Webhook events waiting to be delivered. A row is written in the SAME transaction as the payment
-- change it describes, so an event exists if and only if the change committed.
CREATE TABLE outbox (
    id              BIGSERIAL   PRIMARY KEY,
    -- Sent with every delivery attempt; receivers use it to ignore duplicates.
    event_id        TEXT        NOT NULL UNIQUE,
    endpoint_id     BIGINT      NOT NULL REFERENCES webhook_endpoints (id),
    type            TEXT        NOT NULL,
    payload         TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'delivered', 'dead')),
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at    TIMESTAMPTZ
);

-- The dispatcher's query: pending events that are due, oldest first.
CREATE INDEX outbox_due_idx ON outbox (next_attempt_at, id) WHERE status = 'pending';
