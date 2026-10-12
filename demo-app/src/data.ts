import { useEffect, useState } from 'react';
import type { Payment, User } from './api';

/** What every screen gets: who we're viewing as, everyone (to show names), and a refresh signal. */
export type ViewProps = { me: User; users: User[]; tick: number; refresh: () => void };

/** Runs `load` now and whenever `deps` change; keeps showing the last result while reloading. */
export function useData<T>(load: () => Promise<T>, deps: unknown[]): T | undefined {
  const [data, setData] = useState<T>();
  useEffect(() => {
    let current = true;
    load().then((value) => current && setData(value)).catch(() => {});
    return () => {
      current = false;
    };
  }, deps); // the caller lists what the load depends on
  return data;
}

export const STATUS_LABEL: Record<Payment['status'], string> = {
  created: 'Authorizing',
  unknown: 'Checking with the bank',
  authorized: 'Authorized',
  captured: 'Paid',
  refunded: 'Refunded',
  failed: 'Declined',
  canceled: 'Canceled',
};

export function nameOfWallet(users: User[], walletId: number): string {
  return users.find((user) => user.wallet_id === walletId)?.name ?? `Wallet ${walletId}`;
}

export function time(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}
