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
 * This is what the vendor's **Severity** column shows on the Cloud screen — `sensitives.level`, not
 * `risk.level`. Confirmed row by row against the live project: `xamops-document-bucket` carries
 * `risk.level: CRITICAL` but `sensitives.level: MEDIUM_SENSITIVITY`, and their UI says "Medium".
 * `NOT_SENSITIVE` and null both render as a dash there, not as a band.
 *
 * Only meaningful for datastores — see {@link severityFor}.
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

/** `risk.level` band to its display label. `NONE` is the backend's sentinel for "not evaluated". */
function riskLevelLabel(level: string | null | undefined): string | null {
  switch ((level ?? '').toUpperCase()) {
    case 'CRITICAL': return 'Critical';
    case 'HIGH': return 'High';
    case 'MEDIUM': return 'Medium';
    case 'LOW': return 'Low';
    default: return null;
  }
}

/**
 * The Severity band for a row, or null for a dash.
 *
 * <h3>Why this is per-surface and not one field</h3>
 * The vendor populates two different fields depending on the asset type, and picking either one alone
 * blanks a whole tab:
 *
 * - **Datastores** carry `sensitives.level` (23 of 68 live rows) and that is what the console's Cloud
 *   screen displays, verified against it row by row.
 * - **SaaS principals and endpoint hosts** have `sensitives.level: null` on **every** row — 62 of 62 and
 *   2 of 2 live — while `sensitives.types` is populated, so this is not a scanning gap. Only `risk.level`
 *   is set there, and its live distribution is exactly what the `severity` filter facet reports for
 *   those surfaces.
 *
 * So reusing the Cloud rule on SaaS would have shown a dash in every Severity cell on a tab where the
 * vendor shows Medium, High and Critical.
 *
 * <h3>A known disagreement we are not papering over</h3>
 * The `severity` **filter** facet counts `risk.level` on all three surfaces — the datastore facet reads
 * `NONE 58, CRITICAL 4, LOW 3, MEDIUM 2, HIGH 1`, matching `risk.level` and not `sensitives.level`. So on
 * the Cloud tab, filtering Severity=Critical selects rows whose Severity cell may read Medium. That
 * inconsistency is the vendor's, reproduced rather than invented: matching the filter to the column would
 * mean showing a different severity than their console shows for the same asset, which is the worse of
 * the two confusions when a customer has both products open.
 */
export function severityFor(
  row: { resourceType: string | null; sensitives: { level: string | null }; risk: { level: string } },
): string | null {
  // Keyed on resource_type rather than category so self-hosted datastores (category `on_prem`) follow
  // the datastore rule, which is what the vendor's Cloud screen does with them.
  if (row.resourceType === 'DATASTORE') {
    return sensitivityLabel(row.sensitives.level);
  }
  // Fall back to sensitives for an unrecognised resource type: if the vendor adds one that does populate
  // the field, preferring it matches the datastore precedent.
  return sensitivityLabel(row.sensitives.level) ?? riskLevelLabel(row.risk.level);
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

/** Category accent colours for the grouped sensitive-records panel, matching the console's banding. */
export function categoryAccent(categoryLabel: string | null): { bar: string; chip: string; panel: string } {
  switch (categoryLabel) {
    case 'Personal':
      return {
        bar: 'bg-sky-400', chip: 'bg-sky-50 text-sky-700 border-sky-200',
        panel: 'bg-sky-50/60 border-sky-100',
      };
    case 'IT & Security':
      return {
        bar: 'bg-orange-400', chip: 'bg-orange-50 text-orange-700 border-orange-200',
        panel: 'bg-orange-50/60 border-orange-100',
      };
    case 'Financial':
      return {
        bar: 'bg-violet-400', chip: 'bg-violet-50 text-violet-700 border-violet-200',
        panel: 'bg-violet-50/60 border-violet-100',
      };
    case 'Health':
      return {
        bar: 'bg-emerald-400', chip: 'bg-emerald-50 text-emerald-700 border-emerald-200',
        panel: 'bg-emerald-50/60 border-emerald-100',
      };
    default:
      return {
        bar: 'bg-[rgba(0,0,0,0.2)]',
        chip: 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)] border-[rgba(0,0,0,0.1)]',
        panel: 'bg-[rgba(0,0,0,0.02)] border-[rgba(0,0,0,0.06)]',
      };
  }
}
