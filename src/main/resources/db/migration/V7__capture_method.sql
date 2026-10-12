-- Whether a payment is captured as soon as it's authorized (automatic, what most shops want) or
-- held until the merchant captures it (manual: hotels, shipping, tips). Payments made before this
-- column existed were all manual. New payments always set it explicitly.
ALTER TABLE payments ADD COLUMN capture_method TEXT NOT NULL DEFAULT 'MANUAL'
    CHECK (capture_method IN ('AUTOMATIC', 'MANUAL'));
