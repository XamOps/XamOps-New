/** Number, byte and time formatting helpers for XamPulse. Pure functions, no React. */

export const NO_DATA = '\u2014';

export function formatBytes(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return NO_DATA;
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  let v = Math.abs(n);
  let i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i += 1; }
  const digits = v >= 100 || i === 0 ? 0 : v >= 10 ? 1 : 2;
  return `${n < 0 ? '-' : ''}${v.toFixed(digits)} ${units[i]}`;
}

export function formatRate(bps: number | null | undefined): string {
  if (bps == null || !Number.isFinite(bps)) return NO_DATA;
  return `${formatBytes(bps)}/s`;
}

export function formatPct(v: number | null | undefined, digits = 0): string {
  if (v == null || !Number.isFinite(v)) return NO_DATA;
  return `${v.toFixed(digits)}%`;
}

export function formatCount(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return NO_DATA;
  if (Math.abs(n) >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (Math.abs(n) >= 10_000) return `${(n / 1000).toFixed(0)}k`;
  if (Math.abs(n) >= 1000) return `${(n / 1000).toFixed(1)}k`;
  return Number.isInteger(n) ? String(n) : n.toFixed(1);
}

export function formatUsd(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return NO_DATA;
  return n < 10 ? `$${n.toFixed(2)}` : `$${Math.round(n).toLocaleString('en-US')}`;
}

/** "3m ago", "2h ago". `now` is injectable for tests. */
export function formatAgo(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) return 'never';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return NO_DATA;
  const s = Math.max(0, Math.round((now - t) / 1000));
  if (s < 10) return 'just now';
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}

export function formatClock(isoOrMs: string | number, withMillis = false): string {
  const d = new Date(isoOrMs);
  if (Number.isNaN(d.getTime())) return NO_DATA;
  const p = (x: number, w = 2) => String(x).padStart(w, '0');
  const base = `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
  return withMillis ? `${base}.${p(d.getMilliseconds(), 3)}` : base;
}

export function formatDateTime(isoOrMs: string | number): string {
  const d = new Date(isoOrMs);
  if (Number.isNaN(d.getTime())) return NO_DATA;
  return `${d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })} ${formatClock(isoOrMs)}`;
}

export const RANGE_PRESETS = ['15m', '1h', '6h', '24h', '3d', '7d', '30d'] as const;
export type RangePreset = (typeof RANGE_PRESETS)[number];

const RANGE_MS: Record<RangePreset, number> = {
  '15m': 15 * 60_000, '1h': 3_600_000, '6h': 6 * 3_600_000, '24h': 86_400_000, '3d': 3 * 86_400_000, '7d': 7 * 86_400_000, '30d': 30 * 86_400_000,
};

export function isRangePreset(v: string | null | undefined): v is RangePreset {
  return !!v && (RANGE_PRESETS as readonly string[]).includes(v);
}
export function rangeMs(p: RangePreset): number { return RANGE_MS[p]; }
