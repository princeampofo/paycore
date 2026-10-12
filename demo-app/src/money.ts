// Amounts travel as integers in minor units (1050 = 10.50 AED), exactly like the API.
// The only conversions happen here, with string and integer arithmetic: never floats.

/** "12.5" → 1250. Returns null for anything that isn't a positive amount with at most 2 decimals. */
export function parseAmount(text: string): number | null {
  const match = /^\s*(\d{1,7})(?:[.,](\d{0,2}))?\s*$/.exec(text);
  if (!match) return null;
  const minor = Number(match[1]) * 100 + Number((match[2] ?? '').padEnd(2, '0'));
  return minor > 0 ? minor : null;
}

/** 125050 → "1,250.50 AED". */
export function formatAmount(minor: number, currency = 'AED'): string {
  const sign = minor < 0 ? '−' : '';
  const abs = Math.abs(minor);
  const major = Math.floor(abs / 100).toLocaleString('en-US');
  const cents = String(abs % 100).padStart(2, '0');
  return `${sign}${major}.${cents} ${currency}`;
}
