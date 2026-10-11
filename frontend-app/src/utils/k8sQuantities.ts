/**
 * Kubernetes quantities: parse and format CPU and memory (docs/cloudk8s/ui-revamp-design.md D10, §17).
 *
 * Moved from hooks/useEksDashboard.ts, where a missing or unparseable quantity parsed as 0, which is
 * how "not reported" became "zero" on screen. Here it is `null`, and every formatter renders `null`
 * as "—". Callers that sum requests treat an unset request as 0 on purpose (it reserves nothing) and
 * count those containers; usage and capacity callers keep the `null`.
 *
 * Suffixes follow the Kubernetes quantity grammar: binary `Ki Mi Gi Ti Pi Ei` are powers of 1024,
 * decimal `k M G T P E` powers of 1000 (the old parser read `M` as MiB), and `n u m` are 10^-9,
 * 10^-6 and 10^-3. Memory accepts `m`, which Kubernetes uses when it serializes fractional bytes.
 */

/** What every formatter shows for a value nobody reported. */
export const DASH = '—';

const BINARY: Record<string, number> = {
  Ki: 1024, Mi: 1024 ** 2, Gi: 1024 ** 3, Ti: 1024 ** 4, Pi: 1024 ** 5, Ei: 1024 ** 6,
};

const DECIMAL: Record<string, number> = {
  n: 1e-9, u: 1e-6, m: 1e-3, '': 1, k: 1e3, K: 1e3, M: 1e6, G: 1e9, T: 1e12, P: 1e15, E: 1e18,
};

/** Number, optional decimal exponent, optional suffix: "250m", "1.5", "3372960Ki", "1e3", "2E". */
const QUANTITY = /^([+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)([A-Za-z]{0,2})$/;

/** The quantity in base units (cores or bytes), or null when missing or unparseable. */
export function parseQuantity(input: unknown): number | null {
  if (typeof input === 'number') return Number.isFinite(input) ? input : null;
  if (typeof input !== 'string') return null;
  const text = input.trim();
  if (!text) return null;
  const match = QUANTITY.exec(text);
  if (!match) return null;
  const value = Number(match[1]);
  const suffix = match[2];
  const factor = BINARY[suffix] ?? DECIMAL[suffix];
  if (factor === undefined || !Number.isFinite(value)) return null;
  return value * factor;
}

/** Rounds away floating-point noise ("0.1" cores is 100 millicores, not 100.00000000000001). */
const clean = (value: number) => Math.round(value * 1e6) / 1e6;

/**
 * CPU in millicores: "250m" → 250, "1" → 1000, "1.5" → 1500, "317500648n" → 317.500648.
 * A bare number is cores, like a unitless quantity string. Null for missing or unparseable input.
 */
export function parseCpuMillicores(input: unknown): number | null {
  const cores = parseQuantity(input);
  return cores === null ? null : clean(cores * 1000);
}

/**
 * Memory in MiB: "32Mi" → 32, "1Gi" → 1024, "3400576Ki" → 3320.875, "1G" → 953.67…, "134217728" → 128.
 * A bare number is bytes. Null for missing or unparseable input.
 */
export function parseMemoryMiB(input: unknown): number | null {
  const bytes = parseQuantity(input);
  return bytes === null ? null : clean(bytes / BINARY.Mi);
}

/** Memory in bytes, or null. */
export function parseMemoryBytes(input: unknown): number | null {
  return parseQuantity(input);
}

/** A finite number, or null: for numeric fields the backend may send as null, a string or not at all. */
export function toNumber(input: unknown): number | null {
  if (typeof input === 'number') return Number.isFinite(input) ? input : null;
  if (typeof input === 'string' && input.trim() !== '') {
    const n = Number(input);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

function trimFixed(value: number, digits: number): string {
  return value.toFixed(digits).replace(/\.0+$/, '').replace(/(\.\d*?)0+$/, '$1');
}

/** "250m" under a core, "1.5 cores" above; "—" for null. */
export function formatCpu(millicores: number | null | undefined): string {
  if (millicores === null || millicores === undefined || !Number.isFinite(millicores)) return DASH;
  if (Math.abs(millicores) < 1000) return `${trimFixed(millicores, millicores < 10 && millicores % 1 !== 0 ? 1 : 0)}m`;
  const cores = millicores / 1000;
  return `${trimFixed(cores, 2)} ${cores === 1 ? 'core' : 'cores'}`;
}

/** "512 MiB", "1.5 GiB", "340 KiB"; "—" for null. */
export function formatMemoryMiB(mib: number | null | undefined): string {
  if (mib === null || mib === undefined || !Number.isFinite(mib)) return DASH;
  const abs = Math.abs(mib);
  if (abs > 0 && abs < 1) return `${trimFixed(mib * 1024, 0)} KiB`;
  if (abs < 1024) return `${trimFixed(mib, 0)} MiB`;
  if (abs < 1024 ** 2) return `${trimFixed(mib / 1024, 1)} GiB`;
  return `${trimFixed(mib / 1024 ** 2, 2)} TiB`;
}

/** Bytes as KiB, MiB, GiB; "—" for null. */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || !Number.isFinite(bytes)) return DASH;
  if (Math.abs(bytes) < 1024) return `${trimFixed(bytes, 0)} B`;
  return formatMemoryMiB(bytes / BINARY.Mi);
}

/** "52%", "52.4%" with digits; "—" for null. */
export function formatPercent(pct: number | null | undefined, digits = 0): string {
  if (pct === null || pct === undefined || !Number.isFinite(pct)) return DASH;
  return `${trimFixed(pct, digits)}%`;
}

/** "$48.60"; "—" for null. */
export function formatUsd(amount: number | null | undefined, digits = 2): string {
  if (amount === null || amount === undefined || !Number.isFinite(amount)) return DASH;
  return `$${amount.toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits })}`;
}

/**
 * How old something is: "40s", "12m", "5h", "18d". The agent sends creation times as ISO-8601
 * ("2026-10-09T07:25:39Z"); older payloads and the demo send a ready-made age ("18d"), shown as is.
 * "—" for nothing.
 */
export function formatAge(input: string | number | null | undefined, now: number = Date.now()): string {
  if (input === null || input === undefined || input === '') return DASH;
  const at = typeof input === 'number' ? input : Date.parse(input);
  if (typeof input === 'string' && (Number.isNaN(at) || !/\d{4}-\d{2}-\d{2}/.test(input))) return input;
  const seconds = Math.max(0, Math.floor((now - at) / 1000));
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return `${hours}h`;
  return `${Math.floor(hours / 24)}d`;
}

/** Seconds since the age, for sorting; null when it isn't a timestamp. */
export function ageSortValue(input: string | number | null | undefined, now: number = Date.now()): number | null {
  if (input === null || input === undefined || input === '') return null;
  if (typeof input === 'number') return Math.max(0, now - input);
  if (/\d{4}-\d{2}-\d{2}/.test(input)) {
    const at = Date.parse(input);
    return Number.isNaN(at) ? null : Math.max(0, now - at);
  }
  const short = /^(\d+)([smhd])$/.exec(input.trim());
  if (!short) return null;
  const unit = { s: 1, m: 60, h: 3600, d: 86400 }[short[2] as 's' | 'm' | 'h' | 'd'];
  return Number(short[1]) * unit * 1000;
}
