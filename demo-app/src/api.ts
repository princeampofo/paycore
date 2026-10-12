// A thin, typed wrapper around the paycore API. Field names are the API's (snake_case).

export type User = { key: string; name: string; role: 'CUSTOMER' | 'MERCHANT'; wallet_id: number };

export type Payment = {
  id: number;
  merchant_wallet_id: number;
  amount: number;
  currency: string;
  status: 'created' | 'authorized' | 'captured' | 'refunded' | 'failed' | 'canceled' | 'unknown';
  fee: number;
  customer: string | null;
  capture_method: 'automatic' | 'manual';
  created_at: string;
};

export type Entry = {
  id: number;
  description: string;
  note: string | null;
  created_at: string;
  amount: number; // what this entry did to the wallet asked about
  postings: { account_id: number; amount: number }[];
};

export type ReceivedWebhook = {
  event_id: string;
  type: string;
  payment_id: number;
  received_at: string;
  signature_valid: boolean;
};

export type ApiError = { type: string; code: string; message: string };

export class RequestFailed extends Error {
  constructor(readonly status: number, readonly error: ApiError) {
    super(error.message);
  }
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init);
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new RequestFailed(response.status, body ?? { type: 'api_error', code: 'unknown', message: response.statusText });
  }
  return body as T;
}

/** Every POST carries an Idempotency-Key, so retrying it can never move money twice. */
function post<T>(path: string, body: unknown, idempotencyKey: string): Promise<T> {
  return call<T>(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
    body: body === null ? undefined : JSON.stringify(body),
  });
}

export const api = {
  users: () => call<User[]>('/demo/users'),
  balance: (walletId: number) => call<{ amount: number; currency: string }>(`/v1/wallets/${walletId}/balance`),
  entries: (walletId: number) =>
    call<{ data: Entry[] }>(`/v1/wallets/${walletId}/entries?limit=30`).then((page) => page.data),
  paymentsBy: (customer: string) =>
    call<{ data: Payment[] }>(`/v1/payments?customer=${encodeURIComponent(customer)}&limit=30`).then((r) => r.data),
  paymentsTo: (merchantWalletId: number) =>
    call<{ data: Payment[] }>(`/v1/payments?merchant_wallet_id=${merchantWalletId}&limit=30`).then((r) => r.data),
  payment: (id: number) => call<Payment>(`/v1/payments/${id}`),
  webhooks: () => call<ReceivedWebhook[]>('/demo/webhooks'),

  transfer: (body: { from_wallet_id: number; to_wallet_id: number; amount: number; currency: string; note: string | null }, key: string) =>
    post<{ id: number }>('/v1/transfers', body, key),
  pay: (
    body: { merchant_wallet_id: number; amount: number; currency: string; card_token: string; customer: string; capture_method: 'automatic' | 'manual' },
    key: string,
  ) =>
    post<Payment>('/v1/payments', body, key),
  capture: (paymentId: number, key: string) => post<Payment>(`/v1/payments/${paymentId}/capture`, null, key),
  refund: (paymentId: number, key: string) => post<Payment>(`/v1/payments/${paymentId}/refund`, null, key),
};

export function errorMessage(error: unknown): string {
  if (error instanceof RequestFailed) {
    return error.error.code === 'insufficient_funds' ? 'Not enough money in your wallet.' : error.error.message;
  }
  return 'Could not reach paycore. Try again; it is safe to retry.';
}
