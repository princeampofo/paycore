-- Wallets for the load test: 100 customers with 1,000.00 AED each, and one merchant.
-- On a fresh database the customers are ids 4-103 and the merchant is 104 (the script's defaults).
BEGIN;
INSERT INTO accounts (type, currency, owner)
SELECT 'WALLET', 'AED', 'load-' || n FROM generate_series(1, 100) AS n;
INSERT INTO accounts (type, currency, owner) VALUES ('WALLET', 'AED', 'load-merchant');
INSERT INTO balances (account_id, allow_negative) SELECT id, false FROM accounts WHERE owner LIKE 'load-%';

-- One balanced top-up entry: external_funding −100,000.00, each customer +1,000.00.
INSERT INTO journal_entries (description) VALUES ('load test top-up');
INSERT INTO postings (entry_id, account_id, amount)
SELECT currval('journal_entries_id_seq'), id, 100000 FROM accounts WHERE owner LIKE 'load-%' AND owner <> 'load-merchant';
INSERT INTO postings (entry_id, account_id, amount)
SELECT currval('journal_entries_id_seq'), id, -100000 * 100 FROM accounts WHERE type = 'EXTERNAL_FUNDING';
UPDATE balances SET balance = 100000
WHERE account_id IN (SELECT id FROM accounts WHERE owner LIKE 'load-%' AND owner <> 'load-merchant');
UPDATE balances SET balance = balance - 100000 * 100
WHERE account_id = (SELECT id FROM accounts WHERE type = 'EXTERNAL_FUNDING');
COMMIT;

SELECT min(id) AS first_wallet, max(id) AS last_wallet FROM accounts WHERE owner LIKE 'load-%' AND owner <> 'load-merchant';
SELECT id AS merchant FROM accounts WHERE owner = 'load-merchant';
