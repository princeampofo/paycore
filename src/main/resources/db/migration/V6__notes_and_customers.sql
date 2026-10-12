-- An optional message on a transfer ("lunch on Friday"), shown in the wallet's activity.
ALTER TABLE journal_entries ADD COLUMN note TEXT;

-- Who paid, as the client identifies them (Stripe calls this the customer). Optional; the card
-- pays, not a wallet, so this is just a label for listing a customer's payments.
ALTER TABLE payments ADD COLUMN customer TEXT;

-- For listing a merchant's or a customer's payments, newest first.
CREATE INDEX payments_merchant_idx ON payments (merchant_wallet_id, id);
CREATE INDEX payments_customer_idx ON payments (customer, id) WHERE customer IS NOT NULL;
