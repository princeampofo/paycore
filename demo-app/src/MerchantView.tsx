import { useRef, useState } from 'react';
import { api, errorMessage, type Payment } from './api';
import { Activity } from './Activity';
import { STATUS_LABEL, time, useData, type ViewProps } from './data';
import { formatAmount } from './money';

/** The merchant's side: incoming card payments to capture or refund, and the webhooks it received. */
export function MerchantView({ me, users, tick, refresh }: ViewProps) {
  const balance = useData(() => api.balance(me.wallet_id), [me.wallet_id, tick]);
  const payments = useData(() => api.paymentsTo(me.wallet_id), [me.wallet_id, tick]);
  const entries = useData(() => api.entries(me.wallet_id), [me.wallet_id, tick]);
  const webhooks = useData(() => api.webhooks(), [tick]);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // One idempotency key per (payment, action): clicking Capture twice sends the same request,
  // which the server answers from its saved response instead of capturing again.
  const keys = useRef(new Map<string, string>());
  function keyFor(action: string): string {
    if (!keys.current.has(action)) keys.current.set(action, crypto.randomUUID());
    return keys.current.get(action)!;
  }

  async function act(payment: Payment, action: 'capture' | 'refund') {
    const id = `${payment.id}:${action}`;
    setBusy(id);
    setError(null);
    try {
      await (action === 'capture' ? api.capture : api.refund)(payment.id, keyFor(id));
      refresh();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(null);
    }
  }

  const customerName = (payment: Payment) =>
    users.find((user) => user.key === payment.customer)?.name ?? payment.customer ?? 'Card';

  return (
    <>
      <section className="card balance">
        <div className="label">{me.name}'s balance</div>
        <div className="amount">{balance ? formatAmount(balance.amount) : '…'}</div>
        <div className="muted">paycore keeps 2.90% of each captured payment</div>
      </section>

      <section className="card">
        <h2>Incoming payments</h2>
        {error && <p className="error">{error}</p>}
        {payments?.length === 0 && <p className="muted">No payments yet.</p>}
        <ul className="list">
          {payments?.map((payment) => (
            <li key={payment.id}>
              <div>
                <div>
                  {customerName(payment)} · {formatAmount(payment.amount)}
                </div>
                <div className="muted small">
                  #{payment.id} · {time(payment.created_at)} · <span className={`pill ${payment.status}`}>{STATUS_LABEL[payment.status]}</span>
                </div>
              </div>
              {payment.status === 'authorized' && (
                <button disabled={busy === `${payment.id}:capture`} onClick={() => act(payment, 'capture')}>
                  Capture
                </button>
              )}
              {payment.status === 'captured' && (
                <button className="secondary" disabled={busy === `${payment.id}:refund`} onClick={() => act(payment, 'refund')}>
                  Refund
                </button>
              )}
            </li>
          ))}
        </ul>
      </section>

      <section className="card">
        <h2>Webhooks received</h2>
        {webhooks?.length === 0 && <p className="muted">None yet.</p>}
        <ul className="list">
          {webhooks?.map((webhook) => (
            <li key={webhook.event_id}>
              <div>
                <div>{webhook.type}</div>
                <div className="muted small">
                  payment #{webhook.payment_id} · {time(webhook.received_at)}
                </div>
              </div>
              <div className={webhook.signature_valid ? 'in small' : 'out small'}>
                {webhook.signature_valid ? 'signature ✓' : 'bad signature'}
              </div>
            </li>
          ))}
        </ul>
      </section>

      <Activity me={me} users={users} entries={entries} />
    </>
  );
}
