import { useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage, type Payment, type User } from './api';
import { formatAmount, parseAmount } from './money';

type Props = { me: User; merchant: User; onChange: () => void; onClose: () => void };

const CARDS = [
  { token: 'tok_visa', label: 'Visa test card' },
  { token: 'tok_declined', label: 'Test card that gets declined' },
];
// Most shops take the money straight away. Holding it first is for when the final amount or the
// delivery comes later, like a hotel bill or an order that ships.
const CAPTURE = [
  { method: 'automatic', label: 'Right away (most shops)' },
  { method: 'manual', label: 'When the café captures it (a hold, like a hotel)' },
] as const;
const SETTLED: Payment['status'][] = ['captured', 'failed', 'refunded', 'canceled'];

export function PayMerchant({ me, merchant, onChange, onClose }: Props) {
  const [amountText, setAmountText] = useState('');
  const [card, setCard] = useState(CARDS[0].token);
  const [captureMethod, setCaptureMethod] = useState<'automatic' | 'manual'>('automatic');
  const [idempotencyKey] = useState(() => crypto.randomUUID()); // see SendMoney
  const [submitting, setSubmitting] = useState(false);
  const [payment, setPayment] = useState<Payment | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Until the payment settles, check on it every second: an "unknown" payment resolves in the
  // background, and "authorized" becomes "paid" when the merchant captures it.
  useEffect(() => {
    if (!payment || SETTLED.includes(payment.status)) return;
    const timer = setInterval(() => api.payment(payment.id).then(setPayment).catch(() => {}), 1000);
    return () => clearInterval(timer);
  }, [payment?.id, payment?.status]);

  async function pay(event: FormEvent) {
    event.preventDefault();
    const amount = parseAmount(amountText);
    if (amount === null) {
      setError('Enter an amount like 12.50');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const created = await api.pay(
        { merchant_wallet_id: merchant.wallet_id, amount, currency: 'AED', card_token: card, customer: me.key, capture_method: captureMethod },
        idempotencyKey,
      );
      setPayment(created);
      onChange();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSubmitting(false);
    }
  }

  if (submitting || payment) {
    return (
      <section className="card">
        <h2>
          {payment ? formatAmount(payment.amount) : formatAmount(parseAmount(amountText) ?? 0)} to {merchant.name}
        </h2>
        <PaymentProgress status={payment?.status ?? 'created'} />
        {payment && <button onClick={onClose}>Done</button>}
      </section>
    );
  }
  return (
    <form className="card" onSubmit={pay}>
      <h2>Pay {merchant.name}</h2>
      <label>
        Amount (AED)
        <input inputMode="decimal" placeholder="0.00" value={amountText} onChange={(e) => setAmountText(e.target.value)} />
      </label>
      <label>
        Card
        <select value={card} onChange={(event) => setCard(event.target.value)}>
          {CARDS.map((option) => (
            <option key={option.token} value={option.token}>
              {option.label}
            </option>
          ))}
        </select>
      </label>
      <label>
        Take the money
        <select value={captureMethod} onChange={(event) => setCaptureMethod(event.target.value as 'automatic' | 'manual')}>
          {CAPTURE.map((option) => (
            <option key={option.method} value={option.method}>
              {option.label}
            </option>
          ))}
        </select>
      </label>
      {error && <p className="error">{error}</p>}
      <div className="actions">
        <button type="button" className="secondary" onClick={onClose}>
          Cancel
        </button>
        <button type="submit">Pay</button>
      </div>
    </form>
  );
}

const STEPS = ['Authorizing', 'Authorized', 'Paid'];
const MESSAGES: Record<Payment['status'], string> = {
  created: 'Contacting your bank…',
  unknown: "Your bank didn't answer in time. We're checking with them, and you'll never be charged twice.",
  authorized: 'Approved. The money is held until the café captures it.',
  captured: 'Paid.',
  failed: 'Declined. You were not charged.',
  refunded: 'Refunded to your card.',
  canceled: 'Canceled. You were not charged.',
};

function PaymentProgress({ status }: { status: Payment['status'] }) {
  const reached = status === 'captured' || status === 'refunded' ? 2 : status === 'authorized' ? 1 : 0;
  const stopped = status === 'failed' || status === 'canceled';
  return (
    <>
      <ol className="steps">
        {STEPS.map((step, index) => (
          <li key={step} className={stopped && index === 0 ? 'stopped' : index <= reached ? 'reached' : ''}>
            {step}
          </li>
        ))}
      </ol>
      <p className={stopped ? 'error' : status === 'captured' ? 'success' : 'muted'}>{MESSAGES[status]}</p>
    </>
  );
}
