-- One row per Idempotency-Key a client has sent. It lets a client retry a POST safely: the
-- first request does the work, every retry gets the saved response back.
CREATE TABLE idempotency_keys (
    -- The primary key is what makes concurrent duplicates safe: only one INSERT of a key can win.
    key             TEXT        PRIMARY KEY,
    -- SHA-256 of endpoint + request body, to spot the same key being reused for a different request.
    request_hash    TEXT        NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('in_progress', 'completed')),
    response_status INT,
    response_body   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT completed_has_response
        CHECK (status = 'in_progress' OR (response_status IS NOT NULL AND response_body IS NOT NULL))
);
