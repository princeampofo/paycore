# paycore

A wallet and payments API on a double-entry ledger in Postgres, built to never lose money or
double-charge under concurrency and retries.

## Running

Requires Java 21 and Docker.

```
./mvnw test                                        # real Postgres 16 via Testcontainers
docker compose -f deploy/docker-compose.yml up -d  # Postgres (5433) and mock processor (8081)
./mvnw spring-boot:run                             # API on localhost:8080
```

## API

```
POST /v1/transfers               wallet → wallet
POST /v1/payments                create + authorize a card payment to a merchant
POST /v1/payments/{id}/capture   (also /cancel, /refund)
GET  /v1/payments/{id}
GET  /v1/wallets/{id}/balance
POST /v1/webhook_endpoints       where to send a merchant's payment events
```

Every `POST` requires an `Idempotency-Key` header.

Amounts are integers in minor units (`2500` = 25.00 AED). Errors look like
`{"type": "invalid_request_error", "code": "insufficient_funds", "message": "..."}`.

## Guarantees

- **Balanced ledger:** every journal entry's postings sum to zero, and cached balances always equal
  the sum of their postings.
- **Append-only history:** a trigger rejects edits and deletes on ledger tables. Mistakes are fixed
  with reversing entries.
- **No overdrafts:** balance rows are locked (`SELECT ... FOR UPDATE`) in account-id order, which
  also prevents deadlocks. A `CHECK` constraint backs up the application check.
- **Payments follow a state machine:** `created → authorized → captured → refunded` (or `canceled`,
  `failed`, `unknown`). Illegal moves return `409`, and a version column stops concurrent captures.
  The processor is never called inside a database transaction.
- **Timeouts never double-charge:** a payment whose processor call times out becomes `unknown`. A
  background job then asks the processor about that exact charge (by a reference we chose) and records
  the real outcome once. It never re-sends the charge.
- **No lost webhooks:** every payment change writes its event to an `outbox` table in the same
  transaction. A dispatcher claims events with `FOR UPDATE SKIP LOCKED` and delivers them signed
  (HMAC-SHA256), retrying with exponential backoff until delivered or marked `dead`. Delivery is
  at-least-once; receivers dedupe on the event id.
- **Safe retries:** the same `Idempotency-Key` replays the saved response without moving money again.
  The key's primary key makes concurrent duplicates safe. Reusing a key for a different request
  returns `422`, and retrying while the first is still running returns `409`.

## Tests

- `ConcurrentTransfersTest`: 1,000 concurrent transfers from one wallet never overdraw or deadlock.
- `DuplicateRequestsTest`: 50 concurrent requests with one idempotency key charge exactly once.
- `ProcessorTimeoutTest`: the processor charges but times out. The payment goes `unknown`, resolves to
  `authorized`, and the customer is charged exactly once.
- `WebhookDeliveryTest`: the receiver goes down while 20 events queue up. Once it's back, all 20
  arrive, correctly signed.
- `PaymentLifecycleTest`: authorize, capture, refund, cancel, declines, illegal transitions.
- `LedgerPropertyTest`: random sequences of transfers, payments, captures, refunds and cancels keep
  every invariant after every step.
- Plus unit and integration tests for `Money`, journal entries, the state machine, webhook signatures,
  the ledger, the API and idempotency.
