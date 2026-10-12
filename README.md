# paycore

A wallet and payments API built on an append-only, double-entry ledger in Postgres, written to
stay correct under concurrency.

## Running

Requires Java 21 and Docker.

```
./mvnw test                                        # starts a real Postgres 16 via Testcontainers
docker compose -f deploy/docker-compose.yml up -d  # Postgres on localhost:5433
./mvnw spring-boot:run                             # API on localhost:8080
```

## API

```
POST /v1/transfers               wallet → wallet
GET  /v1/wallets/{id}/balance
```

Amounts are integers in minor units (`2500` = 25.00 AED). Every `POST` requires an
`Idempotency-Key` header (see below). Errors share one shape:

```json
{"type": "invalid_request_error", "code": "insufficient_funds", "message": "Wallet 4 has insufficient funds"}
```

## Ledger design

Every movement of money is one **journal entry** made of **postings** (signed amounts on accounts)
that sum to zero. Money only ever moves; it is never created or destroyed.

| Table | Purpose |
|---|---|
| `accounts` | type (`WALLET`, `HOLDS`, `FEE_REVENUE`, `EXTERNAL_FUNDING`), currency, owner |
| `journal_entries` | one business event |
| `postings` | signed amount on one account in one entry |
| `balances` | cached balance per account; the row locked while posting |

**Invariants**, checked by `LedgerInvariants`:

1. Every journal entry's postings sum to zero. (`JournalEntry` can't be constructed unbalanced.)
2. Each cached balance equals the sum of that account's postings. (Updated in the same transaction.)
3. Wallets never go negative. (Application check under the row lock, plus a `CHECK` constraint.)

**Append-only:** a trigger rejects `UPDATE`, `DELETE` and `TRUNCATE` on `journal_entries` and
`postings`. Mistakes are corrected with reversing entries.

**Concurrency:** `Ledger.post` locks the affected `balances` rows with `SELECT ... FOR UPDATE` in
ascending account id order, so concurrent transfers can't spend the same money twice and two
opposite transfers can't deadlock.

## Idempotency

Every `POST` takes an `Idempotency-Key` header, so clients can retry safely after a timeout.

- **New key:** it's claimed by inserting it as `in_progress`. The primary key makes concurrent
  duplicates safe, because only one insert can win. The work runs, and the response is saved as
  `completed` in the same transaction as the ledger postings.
- **Same key, same request:** the saved response is replayed byte for byte, with no new side effects.
- **Same key, different request** (compared by a SHA-256 of endpoint and body): `422 idempotency_key_reused`.
- **Same key while the first request is still running:** `409 request_in_progress`, retry later.
- **Failed request:** the transaction rolled back and nothing happened, so the key is released and
  a retry runs fresh.
- Keys expire after 24 hours.

## Tests

- `ConcurrentTransfersTest`: 1,000 concurrent transfers out of one wallet holding enough for 500.
  Exactly 500 succeed, the balance ends at exactly zero, and the ledger stays balanced. Also 1,000
  opposing transfers between two wallets with no deadlock.
- `DuplicateRequestsTest`: 50 concurrent HTTP requests with the same idempotency key. Exactly one
  transfer happens, and every client gets an identical response.
- `IdempotencyTest`: replay, key reuse, in-progress conflicts, release on failure, expiry, missing header.
- `LedgerPropertyTest` (jqwik): random sequences of transfers, with every invariant and every balance
  checked against an in-memory model after each step.
- `LedgerTest`: overdrafts write nothing, history can't be edited, the database refuses negative
  wallets on its own, and the invariant check spots a corrupted balance.
- `TransferApiTest`: the HTTP contract, including error codes.
- `MoneyTest`, `JournalEntryTest`: unit tests for the value types.
