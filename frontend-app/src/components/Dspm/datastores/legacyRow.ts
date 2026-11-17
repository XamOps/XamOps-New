/**
 * Adapter from a Datastores list row to the row shape the existing SaaS drawer takes.
 *
 * `DspmSaasDrawer` stays byte-identical, and it reads only `name`, `subtypeLabel`, `source.provider` and
 * `profile` from its `row` prop, and only until the asset detail loads. Every other field is filled with
 * the closest honest value so the object satisfies `DspmAssetRow` under `strict`.
 */

import type { DspmAssetRow, DspmSeverity } from '../../../types/dspm';
import type { DatastoreRow } from '../../../types/dspmDatastores';

const LEGACY_LEVELS: ReadonlySet<string> = new Set(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']);

/**
 * The new row's level is an unchecked string; the old row's is the closed `DspmSeverity` union. Anything
 * outside the four bands becomes `NONE`, the old "no finding" sentinel, so an unknown vendor level never
 * reaches the drawer as a severity it has no style for.
 */
export const legacyLevel = (level: string | null): DspmSeverity =>
  level !== null && LEGACY_LEVELS.has(level) ? (level as DspmSeverity) : 'NONE';

export function toLegacySaasRow(row: DatastoreRow): DspmAssetRow {
  const level = legacyLevel(row.risk.level);
  return {
    id: row.id ?? '',
    name: row.name ?? '',
    subtype: row.subtype,
    subtypeLabel: row.subtypeLabel,
    category: row.category ?? '',
    resourceType: 'SAAS_PRINCIPAL',
    sourceId: row.sourceId,
    lifecycle: null,
    risk: { score: row.risk.score, level, policyViolationCount: null, hasFinding: level !== 'NONE' },
    sensitives: {
      recordCount: row.sensitives.recordCount,
      objectCount: 0,
      types: [],
      typeLabels: row.sensitives.typeLabels,
      level: null,
      known: row.scan.status === 'CLASSIFIED',
    },
    source: { provider: row.source.provider, providerLabel: row.source.providerLabel, account: null, region: row.source.region },
    // '' is a valid DspmScanStatus (the union is open), and "Unknown" is the old backend's label for a null status.
    scan: { status: row.scan.status ?? '', statusLabel: row.scan.statusLabel ?? 'Unknown', stage: null, lastScannedAt: row.scan.displayAt },
    profile: null,
  };
}
