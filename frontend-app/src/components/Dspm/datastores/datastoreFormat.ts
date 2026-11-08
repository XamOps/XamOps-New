/**
 * Pure formatting rules for the Datastores page and drawer.
 *
 * The backend sends numbers and epoch seconds; everything a person reads is shaped here, in the browser,
 * so relative times follow the viewer's clock and time zone and demo mode exercises the same code as
 * production. Sizes (base 1024) and the Scan History date reuse the existing helpers in `../dspmFormat`,
 * re-exported below so the drawer has one import for formatting.
 */

import { formatBytes, formatConsoleDate } from '../dspmFormat';

export { formatBytes, formatConsoleDate };

const UNITS: ReadonlyArray<[number, string]> = [
  [1e3, 'K'],
  [1e6, 'M'],
  [1e9, 'B'],
  [1e12, 'T'],
];

/**
 * Count abbreviated to 3 significant digits: 909, 1.56K, 7.78K, 4.4K, 866K, 1M.
 *
 * Below 1,000 the integer itself. Otherwise the unit is picked by magnitude and the scaled value rounded
 * to 3 significant digits; when that rounding reaches 1,000 (999,999 → "1000K") it moves up one unit.
 */
export function abbreviate(n: number): string {
  if (Math.abs(n) < 1000) return String(n);
  let unit = 0;
  while (unit < UNITS.length - 1 && Math.abs(n) >= UNITS[unit + 1][0]) unit += 1;
  let scaled = Number((n / UNITS[unit][0]).toPrecision(3));
  if (Math.abs(scaled) >= 1000 && unit < UNITS.length - 1) {
    unit += 1;
    scaled = Number((n / UNITS[unit][0]).toPrecision(3));
  }
  return `${scaled}${UNITS[unit][1]}`;
}

/** Risk score to one decimal, half up: 8.44 → "8.4", 8.45 → "8.5", 3 → "3.0". */
export function formatRiskScore(score: number): string {
  return (Math.round(score * 10 + 1e-9) / 10).toFixed(1);
}

/**
 * Scan times younger than this many days show as "{n}d ago"; older ones as a date. The console's exact
 * cut-off is unconfirmed (23 days was relative, 52 days absolute), so it lives in this one constant.
 */
export const RELATIVE_TIME_CUTOFF_DAYS = 30;

const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

/**
 * The Scan Status cell's second line: "1d ago", "5h ago", "0m ago", or an absolute date once the time is
 * `RELATIVE_TIME_CUTOFF_DAYS` old. Units are whole and floored; a future time counts as now.
 */
export function formatScanTime(epochSeconds: number | null | undefined, nowMs: number): string | null {
  if (epochSeconds === null || epochSeconds === undefined) return null;
  const age = Math.max(0, nowMs - epochSeconds * 1000);
  if (age >= RELATIVE_TIME_CUTOFF_DAYS * DAY_MS) return formatListDate(epochSeconds);
  if (age >= DAY_MS) return `${Math.floor(age / DAY_MS)}d ago`;
  if (age >= HOUR_MS) return `${Math.floor(age / HOUR_MS)}h ago`;
  return `${Math.floor(age / MINUTE_MS)}m ago`;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** Absolute list time in the viewer's time zone: "12 Sep 2026, 8:51 PM" (zero-padded day, unpadded hour). */
export function formatListDate(epochSeconds: number): string {
  const d = new Date(epochSeconds * 1000);
  const hours = d.getHours();
  const day = String(d.getDate()).padStart(2, '0');
  const minutes = String(d.getMinutes()).padStart(2, '0');
  return `${day} ${MONTHS[d.getMonth()]} ${d.getFullYear()}, ${hours % 12 || 12}:${minutes} ${hours < 12 ? 'AM' : 'PM'}`;
}

/**
 * The Sensitive Data figure. Only a datastore that was never scanned shows "-"; every other status shows
 * the count, so a failed scan with nothing found reads "0".
 */
export function sensitiveDataText(status: string | null, count: number): string {
  return status === 'NOT_SCANNED' ? '-' : abbreviate(count);
}

export interface TrendBadge {
  text: string;
  direction: 'up' | 'down';
}

/** U+2212, the typographic minus the console uses in its badges. */
const MINUS = '\u2212';

/**
 * Badge for a KPI trend, from its first and last points; null hides the badge.
 *
 * `percent`: the change as a percentage of the first point, rounded up to a whole percent (896,676 →
 * 896,678 is "+1%"). Hidden when the first point is 0. `delta`: last − first ("+19", "−3").
 * Hidden when there are fewer than 2 points or the trend is flat.
 */
export function trendBadge(points: ReadonlyArray<{ value: number }>, kind: 'percent' | 'delta'): TrendBadge | null {
  if (points.length < 2) return null;
  const first = points[0].value;
  const last = points[points.length - 1].value;
  if (last === first) return null;
  const direction = last < first ? 'down' : 'up';
  const sign = direction === 'up' ? '+' : MINUS;
  const change = Math.abs(last - first);
  if (kind === 'percent') {
    if (first === 0) return null;
    return { text: `${sign}${Math.ceil((change * 100) / first)}%`, direction };
  }
  return { text: `${sign}${change}`, direction };
}

/** "71 - 72 of 72" for an occurrence page, or null when there is nothing to show. */
export function occurrenceRange(skip: number, limit: number, total: number): { from: number; to: number; total: number } | null {
  if (total === 0) return null;
  return { from: skip + 1, to: Math.min(skip + limit, total), total };
}

/**
 * Label for the drawer's console link, or null when the link must not render: anything that is not an
 * absolute `https:` URL.
 */
export function consoleLinkLabel(url: string | null | undefined): string | null {
  if (!url) return null;
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return null;
  }
  if (parsed.protocol !== 'https:') return null;
  const host = parsed.hostname.toLowerCase();
  return host === 'aws.amazon.com' || host.endsWith('.aws.amazon.com') ? 'Go to AWS' : 'Go to console';
}

/** Lower-cased text after the last "." of a path's basename, or null when there is none. */
export function extensionOf(name: string | null | undefined): string | null {
  if (!name) return null;
  const base = name.slice(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
  const dot = base.lastIndexOf('.');
  if (dot < 0 || dot === base.length - 1) return null;
  return base.slice(dot + 1).toLowerCase();
}
