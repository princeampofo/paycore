-- Finds the payments whose outcome we don't know: used by the status-check job and the
-- payments_unknown gauge. Partial, so it only holds the few rows that are in these states.
CREATE INDEX payments_unresolved_idx ON payments (status, updated_at) WHERE status IN ('UNKNOWN', 'CREATED');
