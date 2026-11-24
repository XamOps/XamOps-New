/**
 * The one money formatter for AutoSpotting (design-ux-ops.md §2.4, FR-UI-05).
 *
 * Always shows the ISO currency code (`USD 820`), never a symbol that implies a currency (the rupee
 * sign on USD figures was XC-21). A null amount renders "Not priced", never 0 (AC-UI-04).
 */
export const NOT_PRICED = 'Not priced';

export interface FormatMoneyOptions {
  /** Append "/mo". */
  perMonth?: boolean;
  /** An optional label appended in parentheses, for plain-text use. */
  label?: string;
}

export function formatMoney(
  amount: number | null | undefined,
  currency: string = 'USD',
  opts: FormatMoneyOptions = {},
): string {
  if (amount === null || amount === undefined || Number.isNaN(amount)) return NOT_PRICED;
  const digits = Math.abs(amount) >= 100 ? 0 : 2;
  const num = amount.toLocaleString('en-US', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  });
  let out = `${currency} ${num}`;
  if (opts.perMonth) out += '/mo';
  if (opts.label) out += ` (${opts.label})`;
  return out;
}
