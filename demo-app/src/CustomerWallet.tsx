import { useState } from 'react';
import { api } from './api';
import { Activity } from './Activity';
import { useData, type ViewProps } from './data';
import { formatAmount } from './money';
import { PayMerchant } from './PayMerchant';
import { SendMoney } from './SendMoney';

export function CustomerWallet({ me, users, tick, refresh }: ViewProps) {
  const [screen, setScreen] = useState<'home' | 'send' | 'pay'>('home');
  const balance = useData(() => api.balance(me.wallet_id), [me.wallet_id, tick]);
  const entries = useData(() => api.entries(me.wallet_id), [me.wallet_id, tick]);
  const payments = useData(() => api.paymentsBy(me.key), [me.key, tick]);
  const merchant = users.find((user) => user.role === 'MERCHANT')!;

  // Authorized card payments: the money is held, waiting for the merchant to capture it.
  const pending = (payments ?? []).filter((p) => p.status === 'authorized').reduce((sum, p) => sum + p.amount, 0);
  const backHome = () => {
    setScreen('home');
    refresh();
  };

  return (
    <>
      <section className="card balance">
        <div className="label">{me.name}'s balance</div>
        <div className="amount">{balance ? formatAmount(balance.amount) : '…'}</div>
        {pending > 0 && <div className="muted">{formatAmount(pending)} on your card, waiting for capture</div>}
        {screen === 'home' && (
          <div className="actions">
            <button onClick={() => setScreen('send')}>Send money</button>
            <button onClick={() => setScreen('pay')}>Pay {merchant.name}</button>
          </div>
        )}
      </section>

      {screen === 'send' && <SendMoney me={me} users={users} onSent={refresh} onClose={backHome} />}
      {screen === 'pay' && <PayMerchant me={me} merchant={merchant} onChange={refresh} onClose={backHome} />}

      <Activity me={me} users={users} entries={entries} payments={payments} />
    </>
  );
}
