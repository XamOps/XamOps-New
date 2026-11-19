/**
 * The Data Lineage page's "+ More filters" dimensions per level and their names (build 4 design 6.6,
 * FR-FLT-1). Pure. The keys are the backend's filter parameters (`severity`, `datastoreName`, `label`).
 */

/** Policy level: Severity, Data Store and Labels; datastore level: Severity only (D2). */
export const LINEAGE_FILTER_DIMENSIONS: Record<'policy' | 'datastore', string[]> = {
  policy: ['severity', 'datastoreName', 'label'],
  datastore: ['severity'],
};

const LABELS: Record<string, string> = {
  severity: 'Severity',
  datastoreName: 'Data Store',
  label: 'Labels',
};

/** A dimension's name in the More-filters list. */
export function lineageFilterLabel(dimension: string): string {
  return LABELS[dimension] ?? dimension;
}
