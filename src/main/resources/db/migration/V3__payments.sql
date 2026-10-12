-- A card payment to a merchant. Its status follows the state machine in PaymentStatus.java.
CREATE TABLE payments (
    id                 BIGSERIAL   PRIMARY KEY,
    merchant_wallet_id BIGINT      NOT NULL REFERENCES accounts (id),
    amount             BIGINT      NOT NULL CHECK (amount > 0),
    currency           CHAR(3)     NOT NULL,
    -- A fake test token such as 'tok_visa'. Never a card number.
    card_token         TEXT        NOT NULL,
    status             TEXT        NOT NULL CHECK (status IN
                           ('CREATED', 'AUTHORIZED', 'CAPTURED', 'REFUNDED', 'FAILED', 'CANCELED', 'UNKNOWN')),
    -- Chosen by us and sent with the charge, so we can ask the processor about it even when
    -- its response never reached us.
    processor_ref      TEXT        NOT NULL UNIQUE,
    -- The platform fee, fixed at capture time so a refund reverses exactly what was taken.
    fee                BIGINT      NOT NULL DEFAULT 0,
    -- Bumped on every change. An update only succeeds if the version is still the one we read,
    -- so two concurrent captures can't both win.
    version            BIGINT      NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
