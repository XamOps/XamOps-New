// Currency helpers for multi-cloud dashboards.
// AWS spend is reported in USD. GCP is converted to INR upstream.
//
// Azure is reported in whatever currency the subscription is billed in, and the payload now says
// which via `currency`. Pass that code to these helpers wherever it is available; the
// provider-based fallback below is a guess kept only for callers that do not have it yet.

export type CloudProvider = 'AWS' | 'GCP' | 'Azure';

export interface CurrencyMeta {
  symbol: string;
  locale: string;
}

const BY_CODE: Record<string, CurrencyMeta> = {
  USD: { symbol: '$', locale: 'en-US' },
  INR: { symbol: '₹', locale: 'en-IN' },
  EUR: { symbol: '€', locale: 'de-DE' },
  GBP: { symbol: '£', locale: 'en-GB' },
  AUD: { symbol: 'A$', locale: 'en-AU' },
  CAD: { symbol: 'C$', locale: 'en-CA' },
  JPY: { symbol: '¥', locale: 'ja-JP' },
  SGD: { symbol: 'S$', locale: 'en-SG' },
  AED: { symbol: 'AED ', locale: 'en-AE' },
};

/**
 * Symbol and locale for a figure.
 *
 * An explicit ISO currency code always wins. This previously took only the provider and hardcoded
 * ₹ for Azure, asserting in a comment that Azure spend was "converted to INR upstream" — which was
 * never true, no conversion existed anywhere in the Azure path. The result was that an Azure
 * subscription billed in USD, EUR or anything else had its figures labelled ₹ while being neither
 * converted nor flagged. Azure reports its billing currency and the backend now carries it through,
 * so a caller with the payload in hand should pass the code and stop guessing.
 */
export const currencyMeta = (provider?: string, code?: string | null): CurrencyMeta => {
  if (code) {
    const known = BY_CODE[code.toUpperCase()];
    if (known) return known;
    // An unmapped but stated code is still better than a guessed symbol: show the code itself
    // rather than implying a currency the figure is not in.
    return { symbol: `${code.toUpperCase()} `, locale: 'en-US' };
  }
  return provider === 'GCP' || provider === 'Azure'
    ? { symbol: '₹', locale: 'en-IN' }
    : { symbol: '$', locale: 'en-US' };
};

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
