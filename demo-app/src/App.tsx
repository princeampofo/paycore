import { useCallback, useEffect, useState } from 'react';
import { api, type User } from './api';
import { CustomerWallet } from './CustomerWallet';
import { MerchantView } from './MerchantView';

// Money can arrive because of someone else (Bob sends Alice 20 AED), so the wallet re-reads
// its data every couple of seconds as well as after each action.
const REFRESH_MS = 2000;

export default function App() {
  const [users, setUsers] = useState<User[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [viewAs, setViewAs] = useState('alice');
  const [tick, setTick] = useState(0);
  const refresh = useCallback(() => setTick((t) => t + 1), []);

  useEffect(() => {
    api.users().then(setUsers).catch(() => setFailed(true));
  }, []);
  useEffect(() => {
    const timer = setInterval(refresh, REFRESH_MS);
    return () => clearInterval(timer);
  }, [refresh]);

  const me = users?.find((user) => user.key === viewAs);
  return (
    <div className="app">
      <header className="top">
        <span className="brand">paycore</span>
        {users && (
          <label className="view-as">
            View as
            <select value={viewAs} onChange={(event) => setViewAs(event.target.value)}>
              {users.map((user) => (
                <option key={user.key} value={user.key}>
                  {user.name}
                </option>
              ))}
            </select>
          </label>
        )}
      </header>

      {failed && <p className="card error">Can't load the demo users. Is the app running with the "demo" profile?</p>}
      {!failed && !me && <p className="muted">Loading…</p>}
      {/* key: switching user starts the screen fresh instead of showing the previous user's numbers. */}
      {me && users && me.role === 'MERCHANT' && (
        <MerchantView key={me.key} me={me} users={users} tick={tick} refresh={refresh} />
      )}
      {me && users && me.role === 'CUSTOMER' && (
        <CustomerWallet key={me.key} me={me} users={users} tick={tick} refresh={refresh} />
      )}
    </div>
  );
}
