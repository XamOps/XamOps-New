// Currency helpers for multi-cloud dashboards.
// AWS spend is reported in USD; GCP and Azure are converted to INR upstream.

export type CloudProvider = 'AWS' | 'GCP' | 'Azure';

export interface CurrencyMeta {
  symbol: string;
  locale: string;
}

export const currencyMeta = (provider?: string): CurrencyMeta =>
  provider === 'GCP' || provider === 'Azure'
    ? { symbol: '₹', locale: 'en-IN' }
    : { symbol: '$', locale: 'en-US' };

/** Full money string, e.g. "$1,234.56" / "₹1,23,456.00". */
export const fmtMoney = (
  v: number,
  provider?: string,
  opts: Intl.NumberFormatOptions = { minimumFractionDigits: 2, maximumFractionDigits: 2 }
): string => {
  const { symbol, locale } = currencyMeta(provider);
  const n = isFinite(v) && !isNaN(v) ? v : 0;
  return symbol + n.toLocaleString(locale, opts);
};

/** Compact money string, e.g. "$1.2K" / "₹3.4M". */
export const fmtMoneyShort = (v: number, provider?: string, decimals = 0): string => {
  const { symbol } = currencyMeta(provider);
  const n = isFinite(v) && !isNaN(v) ? v : 0;
  if (n >= 1_000_000) return `${symbol}${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000)     return `${symbol}${(n / 1_000).toFixed(1)}K`;
  return `${symbol}${n.toFixed(decimals)}`;
};
