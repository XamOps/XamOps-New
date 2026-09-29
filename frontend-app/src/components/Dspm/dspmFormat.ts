/**
 * Formatting helpers shared by the DSPM table and detail panel.
 *
 * Extracted so the table and the drawer cannot drift: a count shown as "8.63K" in one and "8,630" in
 * the other reads as two different numbers for the same asset.
 */

/**
 * Compact count, matching the vendor console exactly.
 *
 * Derived from their rendering rather than invented: `909`, `1.51K`, `8.63K`, `236.1K`, `42.32M`,
 * `109.49M`. So — plain below 1,000, otherwise up to two decimals with trailing zeros stripped
 * (`236.10K` → `236.1K`, `61.00K` → `61K`) and a K/M/B suffix.
 *
 * Why match them at all: customers read the two products side by side, and `236,100` next to `236.1K`
 * invites a reconciliation question about a number that is identical.
 */
export function compactCount(value: number): string {
  if (!Number.isFinite(value)) return '—';
  const abs = Math.abs(value);
  if (abs < 1000) return String(value);

  const units: Array<[number, string]> = [
    [1_000_000_000, 'B'],
    [1_000_000, 'M'],
    [1_000, 'K'],
  ];

  for (const [divisor, suffix] of units) {
    if (abs >= divisor) {
      const scaled = value / divisor;
      // toFixed(2) then strip a trailing '.00' or a single trailing '0', which is what produces
      // 236.1K rather than 236.10K.
      const text = scaled.toFixed(2).replace(/\.0+$/, '').replace(/(\.\d)0$/, '$1');
      return `${text}${suffix}`;
    }
  }
  return String(value);
}

/** Byte size, e.g. `14.38 GB`. Matches the console's Datastore Size. */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${(bytes / 1024 ** i).toFixed(i === 0 ? 0 : 2)} ${units[i]}`;
}

/**
 * Unix epoch **seconds** to a readable timestamp.
 *
 * The API sends seconds throughout, so a bare `new Date(value)` would land in 1970 — the kind of bug
 * that renders plausibly enough to ship.
 */
export function formatEpochSeconds(seconds: number): string {
  return new Date(seconds * 1000).toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit',
  });
}

/**
 * Sensitivity classification as the console labels it.
 *
 * This is what the vendor's **Severity** column actually shows — `sensitives.level`, not `risk.level`.
 * Confirmed row by row against the live project: `xamops-document-bucket` carries
 * `risk.level: CRITICAL` but `sensitives.level: MEDIUM_SENSITIVITY`, and their UI says "Medium".
 * `NOT_SENSITIVE` and null both render as a dash there, not as a band.
 */
export function sensitivityLabel(level: string | null | undefined): string | null {
  if (!level) return null;
  switch (level.toUpperCase()) {
    case 'CRITICAL_SENSITIVITY': return 'Critical';
    case 'HIGH_SENSITIVITY': return 'High';
    case 'MEDIUM_SENSITIVITY': return 'Medium';
    case 'LOW_SENSITIVITY': return 'Low';
    case 'NOT_SENSITIVE': return null;
    default: return null;
  }
}

/** Badge classes per sensitivity band, following the console's colour weighting. */
export function sensitivityClasses(label: string | null): string {
  switch (label) {
    case 'Critical': return 'bg-rose-50 border-rose-200 text-rose-700';
    case 'High': return 'bg-orange-50 border-orange-200 text-orange-700';
    case 'Medium': return 'bg-amber-50 border-amber-200 text-amber-700';
    case 'Low': return 'bg-sky-50 border-sky-200 text-sky-700';
    default: return 'bg-[rgba(0,0,0,0.04)] border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.55)]';
  }
}

/** Category accent colours for the grouped sensitive-records panel. */
export function categoryAccent(categoryLabel: string | null): { bar: string; chip: string } {
  switch (categoryLabel) {
    case 'Personal':
      return { bar: 'bg-sky-400', chip: 'bg-sky-50 text-sky-700 border-sky-200' };
    case 'IT & Security':
      return { bar: 'bg-orange-400', chip: 'bg-orange-50 text-orange-700 border-orange-200' };
    case 'Financial':
      return { bar: 'bg-violet-400', chip: 'bg-violet-50 text-violet-700 border-violet-200' };
    case 'Health':
      return { bar: 'bg-emerald-400', chip: 'bg-emerald-50 text-emerald-700 border-emerald-200' };
    default:
      return {
        bar: 'bg-[rgba(0,0,0,0.2)]',
        chip: 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)] border-[rgba(0,0,0,0.1)]',
      };
  }
}
