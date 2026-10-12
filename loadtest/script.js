// Mixed load: 70% wallet transfers, 30% card payments (+ capture), with 5% of transfers retried
// with the same Idempotency-Key the way a client does after a timeout. The merchant has a webhook
// endpoint, so every payment change is also delivered through the outbox. At the end it checks
// that the ledger is still balanced.
//
//   psql ... < loadtest/seed.sql
//   k6 run loadtest/script.js                        (defaults: 200 req/s for 2 minutes)
//   k6 run -e RATE=400 -e DURATION=5m loadtest/script.js
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const FIRST_WALLET = Number(__ENV.FIRST_WALLET || 4);
const WALLETS = Number(__ENV.WALLETS || 100);
const MERCHANT = Number(__ENV.MERCHANT || 104);
// Where the app (inside Docker) sends the merchant's webhooks: the webhook-sink service.
const WEBHOOK_URL = __ENV.WEBHOOK_URL || 'http://webhook-sink:9000/webhooks';
const RUN = Date.now();

export const options = {
  scenarios: {
    mixed: {
      executor: 'constant-arrival-rate', // a fixed number of new requests per second, however slow responses get
      rate: Number(__ENV.RATE || 200),
      timeUnit: '1s',
      duration: __ENV.DURATION || '2m',
      preAllocatedVUs: 400,
      maxVUs: 1000,
    },
  },
  thresholds: {
    'http_req_duration{name:transfer}': ['p(99)<250'],
    // Payments wait on the mock processor (200 ms latency, and 5% of charges time out after 2 s).
    'http_req_duration{name:payment}': ['p(99)<3000'],
    'http_req_duration{name:capture}': ['p(99)<250'],
    'http_req_duration{name:transfer_retry}': ['p(99)<250'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  // Every payment change becomes a webhook to the merchant, so the outbox gets real traffic too.
  const res = post('/v1/webhook_endpoints', { merchant_wallet_id: MERCHANT, url: WEBHOOK_URL }, `w-${RUN}`, 'setup');
  check(res, { 'webhook endpoint registered': (r) => r.status === 201 });
}

export default function () {
  if (Math.random() < 0.7) {
    transfer();
  } else {
    payAndCapture();
  }
}

function transfer() {
  const from = randomWallet();
  let to = randomWallet();
  if (to === from) to = from === FIRST_WALLET ? from + 1 : from - 1;
  const body = { from_wallet_id: from, to_wallet_id: to, amount: 1 + Math.floor(Math.random() * 500), currency: 'AED' };
  const key = newKey('t');

  const res = post('/v1/transfers', body, key, 'transfer');
  check(res, { 'transfer: 201, or 422 insufficient funds': (r) => r.status === 201 || r.status === 422 });

  if (res.status === 201 && Math.random() < 0.05) {
    const retry = post('/v1/transfers', body, key, 'transfer_retry');
    check(retry, { 'retry with the same key replays the same response': (r) => r.status === 201 && r.body === res.body });
  }
}

function payAndCapture() {
  const body = { merchant_wallet_id: MERCHANT, amount: 100 + Math.floor(Math.random() * 10000), currency: 'AED', card_token: 'tok_visa' };
  const key = newKey('p');

  const res = post('/v1/payments', body, key, 'payment');
  check(res, { 'payment: 201': (r) => r.status === 201 });

  // The mock processor declines some cards and times out on some charges (status "unknown").
  if (res.status === 201 && res.json('status') === 'authorized') {
    const capture = post(`/v1/payments/${res.json('id')}/capture`, null, key + '-capture', 'capture');
    check(capture, { 'capture: 200': (r) => r.status === 200 });
  }
}

export function teardown() {
  // ledger_imbalance is recomputed every 10 s; wait so it covers the final state of the ledger.
  sleep(15);
  const scrape = http.get(`${BASE}/actuator/prometheus`).body;
  const imbalance = scrape.match(/^ledger_imbalance (\S+)$/m);
  check(imbalance, { 'ledger_imbalance is 0 after the run': (m) => m !== null && Number(m[1]) === 0 });
  console.log(`ledger_imbalance after the run: ${imbalance ? imbalance[1] : 'missing'}`);
}

function post(path, body, key, name) {
  return http.post(BASE + path, body === null ? null : JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
    tags: { name },
  });
}

function randomWallet() {
  return FIRST_WALLET + Math.floor(Math.random() * WALLETS);
}

function newKey(prefix) {
  return `${prefix}-${RUN}-${__VU}-${__ITER}`;
}
