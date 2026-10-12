import type { Entry, Payment, User } from './api';
import { nameOfWallet, STATUS_LABEL, time } from './data';
import { formatAmount } from './money';

type Props = { me: User; users: User[]; entries?: Entry[]; payments?: Payment[] };
type Item = { key: string; when: string; title: string; detail: string | null; amount: number };

/** Sent, received and card payments, newest first. */
export function Activity({ me, users, entries, payments }: Props) {
  const items = [...fromLedger(me, users, entries ?? []), ...fromPayments(users, payments ?? [])].sort(
    (a, b) => Date.parse(b.when) - Date.parse(a.when),
  );
  return (
    <section className="card">
      <h2>Activity</h2>
      {items.length === 0 && <p className="muted">Nothing yet.</p>}
      <ul className="list">
        {items.map((item) => (
          <li key={item.key}>
            <div>
              <div>{item.title}</div>
              <div className="muted small">
                {time(item.when)}
                {item.detail && ` · ${item.detail}`}
              </div>
            </div>
            <div className={item.amount < 0 ? 'out' : 'in'}>{formatAmount(item.amount)}</div>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** Ledger entries that touched the wallet: transfers, top-ups, and (for a merchant) captures and refunds. */
function fromLedger(me: User, users: User[], entries: Entry[]): Item[] {
  return entries.map((entry) => {
    const other = entry.postings.find((posting) => posting.account_id !== me.wallet_id);
    let title = entry.description;
    if (entry.description === 'transfer' && other) {
      const name = nameOfWallet(users, other.account_id);
      title = entry.amount < 0 ? `Sent to ${name}` : `Received from ${name}`;
    } else if (entry.description === 'top-up') {
      title = 'Added money';
    } else if (entry.description.startsWith('capture payment')) {
      title = 'Card payment received';
    } else if (entry.description.startsWith('refund payment')) {
      title = 'Refund sent';
    }
    return { key: `e${entry.id}`, when: entry.created_at, title, detail: entry.note, amount: entry.amount };
  });
}

/** Card payments come from the card, not the wallet, so they're listed from the payments API. */
function fromPayments(users: User[], payments: Payment[]): Item[] {
  return payments.map((payment) => ({
    key: `p${payment.id}`,
    when: payment.created_at,
    title: `Card payment to ${nameOfWallet(users, payment.merchant_wallet_id)}`,
    detail: STATUS_LABEL[payment.status],
    amount: -payment.amount,
  }));
}
