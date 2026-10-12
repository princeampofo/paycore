-- Double-entry ledger.
--
-- Every movement of money is one journal entry made of postings. A posting is a signed amount
-- in minor units (1050 = 10.50 AED): positive adds to the account, negative takes from it.
-- The postings of an entry always sum to zero, so money only ever moves, it is never created.

CREATE TABLE accounts (
    id         BIGSERIAL   PRIMARY KEY,
    type       TEXT        NOT NULL CHECK (type IN ('WALLET', 'HOLDS', 'FEE_REVENUE', 'EXTERNAL_FUNDING')),
    currency   CHAR(3)     NOT NULL,
    owner      TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- There is exactly one of each system account per currency; there can be many wallets.
CREATE UNIQUE INDEX one_system_account_per_currency ON accounts (type, currency) WHERE type <> 'WALLET';

-- One business event: a transfer, a capture, a refund...
CREATE TABLE journal_entries (
    id          BIGSERIAL   PRIMARY KEY,
    description TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE postings (
    id         BIGSERIAL   PRIMARY KEY,
    entry_id   BIGINT      NOT NULL REFERENCES journal_entries (id),
    account_id BIGINT      NOT NULL REFERENCES accounts (id),
    amount     BIGINT      NOT NULL CHECK (amount <> 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX postings_entry_id_idx ON postings (entry_id);
CREATE INDEX postings_account_id_idx ON postings (account_id, id);

-- Cached balance per account, updated in the same transaction as the postings so reading a
-- balance never means summing the whole history. It is also the row we lock while posting.
CREATE TABLE balances (
    account_id     BIGINT PRIMARY KEY REFERENCES accounts (id),
    balance        BIGINT  NOT NULL DEFAULT 0,
    -- Copied from the account type, because a CHECK constraint can only see its own row.
    allow_negative BOOLEAN NOT NULL,
    -- Last line of defence: even if the application check had a bug, a wallet can't go negative.
    CONSTRAINT balance_not_negative CHECK (allow_negative OR balance >= 0)
);

-- Journal entries and postings are append-only. Mistakes are fixed with a reversing entry,
-- never by editing history, so the ledger is a complete audit trail.
CREATE FUNCTION reject_ledger_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Ledger table % is append-only: % is not allowed', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER journal_entries_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_change();

CREATE TRIGGER postings_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON postings
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_change();

-- System accounts. external_funding represents the outside world (cards, banks): money entering
-- paycore is taken from it, so it is the one account allowed to go negative.
INSERT INTO accounts (type, currency, owner) VALUES
    ('HOLDS', 'AED', 'paycore'),
    ('FEE_REVENUE', 'AED', 'paycore'),
    ('EXTERNAL_FUNDING', 'AED', 'paycore');

INSERT INTO balances (account_id, allow_negative)
SELECT id, type = 'EXTERNAL_FUNDING' FROM accounts;
