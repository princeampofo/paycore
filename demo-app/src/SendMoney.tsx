import { useState, type FormEvent } from 'react';
import { api, errorMessage, type User } from './api';
import { formatAmount, parseAmount } from './money';

type Props = { me: User; users: User[]; onSent: () => void; onClose: () => void };

export function SendMoney({ me, users, onSent, onClose }: Props) {
  const recipients = users.filter((user) => user.role === 'CUSTOMER' && user.key !== me.key);
  const [to, setTo] = useState(recipients[0].wallet_id);
  const [amountText, setAmountText] = useState('');
  const [note, setNote] = useState('');
  // One idempotency key per form, made when it opens. A double click, or pressing Send again after
  // a network error, reuses it: the server treats it as the same request and moves the money once.
  const [idempotencyKey] = useState(() => crypto.randomUUID());
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  async function send(event: FormEvent) {
    event.preventDefault();
    const amount = parseAmount(amountText);
    if (amount === null) {
      setError('Enter an amount like 12.50');
      return;
    }
    setSending(true);
    setError(null);
    try {
      await api.transfer(
        { from_wallet_id: me.wallet_id, to_wallet_id: to, amount, currency: 'AED', note: note.trim() || null },
        idempotencyKey,
      );
      const recipient = recipients.find((user) => user.wallet_id === to)!;
      setDone(`Sent ${formatAmount(amount)} to ${recipient.name}`);
      onSent();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSending(false);
    }
  }

  if (done) {
    return (
      <section className="card">
        <p className="success">✓ {done}</p>
        <button onClick={onClose}>Done</button>
      </section>
    );
  }
  return (
    <form className="card" onSubmit={send}>
      <h2>Send money</h2>
      <label>
        To
        <select value={to} onChange={(event) => setTo(Number(event.target.value))}>
          {recipients.map((user) => (
            <option key={user.key} value={user.wallet_id}>
              {user.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        Amount (AED)
        <input inputMode="decimal" placeholder="0.00" value={amountText} onChange={(e) => setAmountText(e.target.value)} />
      </label>
      <label>
        Note (optional)
        <input maxLength={140} placeholder="What's it for?" value={note} onChange={(e) => setNote(e.target.value)} />
      </label>
      {error && <p className="error">{error}</p>}
      <div className="actions">
        <button type="button" className="secondary" onClick={onClose}>
          Cancel
        </button>
        <button type="submit" disabled={sending}>
          {sending ? 'Sending…' : 'Send'}
        </button>
      </div>
    </form>
  );
}
