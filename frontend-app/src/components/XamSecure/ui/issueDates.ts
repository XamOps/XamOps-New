/**
 * Tiny, pure date helpers for the Issues findings table (spec §5.6).
 *
 * `formatRelative(iso)` → a coarse "2 months ago"-style string using the built-in
 * `Intl.RelativeTimeFormat`, or `null` when the ISO is missing/unparseable.
 * `formatAbsolute(iso)` → a precise "Aug 2, 2026, 08:01 PM"-style string using
 * `Intl.DateTimeFormat`, with an English-only ordinal ("2nd") applied solely when the
 * resolved locale is `en-*`. No dependency — `Intl` is built in.
 */

/** Parse an ISO string to epoch ms, or `null` when missing/invalid. */
function parseIso(iso: string | null | undefined): number | null {
  if (!iso) return null;
  const t = new Date(iso).getTime();
  return Number.isFinite(t) ? t : null;
}

const UNITS: Array<{ limit: number; divisor: number; unit: Intl.RelativeTimeFormatUnit }> = [
  { limit: 60_000, divisor: 1_000, unit: 'second' },
  { limit: 3_600_000, divisor: 60_000, unit: 'minute' },
  { limit: 86_400_000, divisor: 3_600_000, unit: 'hour' },
  { limit: 2_592_000_000, divisor: 86_400_000, unit: 'day' },
  { limit: 31_536_000_000, divisor: 2_592_000_000, unit: 'month' },
  { limit: Infinity, divisor: 31_536_000_000, unit: 'year' },
];

/**
 * Coarsest-unit relative time from now. Returns `null` for null/invalid input so the
 * caller can render an em-dash. Past dates read "… ago"; future dates read "in …".
 */
export function formatRelative(iso: string | null | undefined, now: number = Date.now()): string | null {
  const t = parseIso(iso);
  if (t === null) return null;
  const diff = t - now; // negative => past
  const abs = Math.abs(diff);
  const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
  for (const { limit, divisor, unit } of UNITS) {
    if (abs < limit) {
      const value = Math.round(diff / divisor);
      return rtf.format(value, unit);
    }
  }
  return rtf.format(Math.round(diff / 31_536_000_000), 'year');
}

/** English ordinal suffix for a day-of-month (1 → "st", 2 → "nd", 3 → "rd", else "th"). */
function ordinal(day: number): string {
  const rem100 = day % 100;
  if (rem100 >= 11 && rem100 <= 13) return 'th';
  switch (day % 10) {
    case 1: return 'st';
    case 2: return 'nd';
    case 3: return 'rd';
    default: return 'th';
  }
}

/**
 * Absolute timestamp. English locales get a "2nd"-style ordinal on the day; any non-`en`
 * locale renders the plain localized `Intl.DateTimeFormat` output so the string is never
 * grammatically mismatched. Returns `null` for null/invalid input.
 */
export function formatAbsolute(iso: string | null | undefined): string | null {
  const t = parseIso(iso);
  if (t === null) return null;
  const date = new Date(t);
  const opts: Intl.DateTimeFormatOptions = {
    month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit',
  };
  const fmt = new Intl.DateTimeFormat(undefined, opts);
  const locale = fmt.resolvedOptions().locale;
  if (!locale.startsWith('en')) return fmt.format(date);

  // English: inject the ordinal onto the numeric day via formatToParts.
  const parts = fmt.formatToParts(date);
  return parts
    .map((p) => (p.type === 'day' ? `${p.value}${ordinal(Number(p.value))}` : p.value))
    .join('');
}
