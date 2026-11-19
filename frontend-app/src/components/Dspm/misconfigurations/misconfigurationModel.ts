/**
 * The Misconfigurations page's pure logic: filter options and filtering, Group By, the CSV of both tables
 * and their file names. Everything runs in the browser over the rows the backend already sent.
 *
 * Filters reuse the Datastores filter state (`DatastoreFilterState`) in local mode: Include only, values
 * never sent anywhere. A row matches a dimension when any of its values is selected; dimensions combine
 * with AND.
 */

import type { DatastoreFilterDimension, DatastoreFilterOption, DatastoreFilterOptions, DatastoreRow } from '../../../types/dspmDatastores';
import type { AffectedDatastore, MisconfigurationRow } from '../../../types/dspmMisconfigurations';
import { csvFileName, toCsv, type CsvCell } from '../../../utils/csvExport';
import type { DatastoreFilterState } from '../datastores/datastoreFilters';
import { formatListDate } from '../datastores/datastoreFormat';
import type { DrawerRow } from '../datastores/drawer/DatastoreDrawer';

// ─── Severity ────────────────────────────────────────────────────────────────

const SEVERITY_RANK: Readonly<Record<string, number>> = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1 };

/** Critical 4, High 3, Medium 2, Low 1, anything else 0. */
export const severityRank = (severity: string | null): number => SEVERITY_RANK[severity ?? ''] ?? 0;

/** "Critical" for `CRITICAL`: the label of a severity no row carries. */
export const severityName = (severity: string): string =>
  severity.charAt(0).toUpperCase() + severity.slice(1).toLowerCase();

// ─── Filter dimensions ───────────────────────────────────────────────────────

export const SEVERITY = 'severity';
export const SOURCE = 'source';
export const FRAMEWORK = 'framework';
export const CLOUD_ACCOUNT = 'cloud_account';

/** The "+ More filters" dimensions, in panel order. */
export const MORE_FILTER_DIMENSIONS = [SOURCE, FRAMEWORK, CLOUD_ACCOUNT];

const DIMENSION_LABELS: Readonly<Record<string, string>> = {
  [SEVERITY]: 'Severity', [SOURCE]: 'Source', [FRAMEWORK]: 'Framework', [CLOUD_ACCOUNT]: 'Cloud Account',
};

/** A dimension's name in the filter controls. */
export const dimensionLabel = (dimension: string): string => DIMENSION_LABELS[dimension] ?? dimension;

const SOURCE_LABELS: Readonly<Record<string, string>> = { UPWIND: 'Upwind', MATTERS: 'Matters' };

/** "Upwind" for `UPWIND`, "Matters" for `MATTERS`, any other value as sent. */
export const sourceLabel = (source: string): string => SOURCE_LABELS[source] ?? source;

/** A row's values for one dimension. */
function valuesOf(row: MisconfigurationRow, dimension: string): readonly string[] {
  switch (dimension) {
    case SEVERITY: return row.severity === null ? [] : [row.severity];
    case SOURCE: return row.source === null ? [] : [row.source];
    case FRAMEWORK: return row.frameworks;
    case CLOUD_ACCOUNT: return row.cloudAccounts;
    default: return [];
  }
}

const byLabel = (a: DatastoreFilterOption, b: DatastoreFilterOption) =>
  a.label.toLowerCase().localeCompare(b.label.toLowerCase()) || a.value.localeCompare(b.value);

/** Each value of the dimension with the number of policies carrying it. */
function dimensionOf(rows: readonly MisconfigurationRow[], dimension: string): DatastoreFilterDimension {
  const found = new Map<string, DatastoreFilterOption>();
  for (const row of rows) {
    for (const value of new Set(valuesOf(row, dimension))) {
      const option = found.get(value);
      if (option) {
        option.count = (option.count ?? 0) + 1;
        continue;
      }
      const label = dimension === SEVERITY ? (row.severityLabel ?? severityName(value))
        : dimension === SOURCE ? sourceLabel(value)
          : value;
      found.set(value, { value, label, count: 1 });
    }
  }
  const options = [...found.values()];
  options.sort(dimension === SEVERITY ? (a, b) => severityRank(b.value) - severityRank(a.value) || byLabel(a, b) : byLabel);
  return { key: dimension, options, hasMore: false };
}

/**
 * The options of Severity (highest first) and of the More-filters dimensions (A to Z), each counting the
 * policies that carry the value over all rows.
 */
export function misconfigurationFilterOptions(rows: readonly MisconfigurationRow[]): DatastoreFilterOptions {
  return { dimensions: [SEVERITY, ...MORE_FILTER_DIMENSIONS].map(dimension => dimensionOf(rows, dimension)) };
}

/** Dimension → value → label, the filter controls' label memory. */
export function labelMemoryOf(options: DatastoreFilterOptions): ReadonlyMap<string, ReadonlyMap<string, string>> {
  return new Map(options.dimensions.map(d => [d.key, new Map(d.options.map(o => [o.value, o.label]))]));
}

/** Whether every dimension with a selection has one of the values `valuesFor` gives. */
function matchesFilters(filters: DatastoreFilterState, valuesFor: (dimension: string) => readonly string[]): boolean {
  return Object.entries(filters).every(([dimension, selection]) =>
    selection.values.length === 0 || valuesFor(dimension).some(value => selection.values.includes(value)));
}

/**
 * The rows whose title contains the search (case-insensitive) and that match every filter, in row order.
 * A Cloud Account selection matches a policy when any affected cloud datastore has a selected account.
 */
export function filterMisconfigurations(
  rows: readonly MisconfigurationRow[], search: string, filters: DatastoreFilterState,
): MisconfigurationRow[] {
  const needle = search.trim().toLowerCase();
  return rows.filter(row => (needle === '' || (row.title ?? '').toLowerCase().includes(needle))
    && matchesFilters(filters, dimension => valuesOf(row, dimension)));
}

// ─── Group By ────────────────────────────────────────────────────────────────

export type GroupBy = 'none' | 'severity' | 'framework';

export const GROUP_BY_OPTIONS: ReadonlyArray<{ value: GroupBy; label: string }> = [
  { value: 'none', label: 'Select group by' },
  { value: 'severity', label: 'Severity' },
  { value: 'framework', label: 'Frameworks' },
];

/** One table line: a policy row, and the group it is drawn under (null when not grouped). */
export interface GroupedItem {
  row: MisconfigurationRow;
  groupKey: string | null;
  groupLabel: string | null;
}

/** The group of policies without a framework, drawn last. */
export const NO_FRAMEWORK = '-';

/**
 * The table's lines. Ungrouped: the rows as they are. By severity: highest first, rows in order within
 * each. By framework: a policy under each of its frameworks (A to Z), those without one last under "-".
 */
export function groupMisconfigurations(rows: readonly MisconfigurationRow[], groupBy: GroupBy): GroupedItem[] {
  if (groupBy === 'none') return rows.map(row => ({ row, groupKey: null, groupLabel: null }));
  if (groupBy === 'severity') {
    return [...rows]
      .sort((a, b) => severityRank(b.severity) - severityRank(a.severity))
      .map(row => ({
        row,
        groupKey: row.severity ?? '',
        groupLabel: row.severityLabel ?? (row.severity ? severityName(row.severity) : '-'),
      }));
  }
  const frameworks = [...new Set(rows.flatMap(row => row.frameworks))]
    .sort((a, b) => a.toLowerCase().localeCompare(b.toLowerCase()) || a.localeCompare(b));
  const items: GroupedItem[] = [];
  for (const framework of frameworks) {
    for (const row of rows) {
      if (row.frameworks.includes(framework)) items.push({ row, groupKey: framework, groupLabel: framework });
    }
  }
  for (const row of rows) {
    if (row.frameworks.length === 0) items.push({ row, groupKey: NO_FRAMEWORK, groupLabel: NO_FRAMEWORK });
  }
  return items;
}

/** Lines per group key. */
export function groupSizes(items: readonly GroupedItem[]): ReadonlyMap<string, number> {
  const sizes = new Map<string, number>();
  for (const item of items) {
    if (item.groupKey !== null) sizes.set(item.groupKey, (sizes.get(item.groupKey) ?? 0) + 1);
  }
  return sizes;
}

// ─── Columns and CSV ─────────────────────────────────────────────────────────

export type MisconfigurationColumnId = 'misconfiguration' | 'severity' | 'affected' | 'sensitives' | 'labels' | 'frameworks';

/** Table order; Misconfiguration is always shown. */
export const MISCONFIGURATION_COLUMNS: ReadonlyArray<{ id: MisconfigurationColumnId; label: string }> = [
  { id: 'misconfiguration', label: 'Misconfiguration' },
  { id: 'severity', label: 'Severity' },
  { id: 'affected', label: 'Affected Resources' },
  { id: 'sensitives', label: 'Sensitives' },
  { id: 'labels', label: 'Labels' },
  { id: 'frameworks', label: 'Frameworks' },
];

function misconfigurationCell(id: MisconfigurationColumnId, row: MisconfigurationRow): CsvCell {
  switch (id) {
    case 'misconfiguration': return row.title;
    case 'severity': return row.severityLabel ?? row.severity;
    case 'affected': return row.affectedResources;
    case 'sensitives': return row.sensitiveRecords;
    case 'labels': return row.labels.join(', ');
    case 'frameworks': return row.frameworks.join(', ');
  }
}

/** One line per row, in the order given, with the given columns; headers are the column labels. */
export function misconfigurationCsv(
  rows: readonly MisconfigurationRow[], columns: ReadonlyArray<{ id: MisconfigurationColumnId; label: string }>,
): string {
  return toCsv(columns.map(c => c.label), rows.map(row => columns.map(c => misconfigurationCell(c.id, row))));
}

export type AffectedColumnId = 'datastore' | 'type' | 'account' | 'sensitives' | 'labels' | 'lastScanned' | 'jira';

/** Table order; Affected Datastore is always shown. */
export const AFFECTED_COLUMNS: ReadonlyArray<{ id: AffectedColumnId; label: string }> = [
  { id: 'datastore', label: 'Affected Datastore' },
  { id: 'type', label: 'Datastore Type' },
  { id: 'account', label: 'Account Name' },
  { id: 'sensitives', label: 'Sensitives' },
  { id: 'labels', label: 'Labels' },
  { id: 'lastScanned', label: 'Last scanned time' },
  { id: 'jira', label: 'Jira' },
];

function affectedCell(id: Exclude<AffectedColumnId, 'jira'>, item: AffectedDatastore): CsvCell {
  const row = item.datastore;
  switch (id) {
    case 'datastore': return row.name;
    case 'type': return row.subtypeLabel ?? row.subtype;
    case 'account': return row.source.accountLabel;
    case 'sensitives': return row.sensitives.recordCount;
    case 'labels': return row.sensitives.typeLabels.join(', ');
    case 'lastScanned': return item.lastScannedAt === null ? null : formatListDate(item.lastScannedAt);
  }
}

/** The affected datastores as CSV with the given columns, except Jira (a console-only action). */
export function affectedCsv(
  affected: readonly AffectedDatastore[], columns: ReadonlyArray<{ id: AffectedColumnId; label: string }>,
): string {
  const exported = columns.filter((c): c is { id: Exclude<AffectedColumnId, 'jira'>; label: string } => c.id !== 'jira');
  return toCsv(exported.map(c => c.label), affected.map(item => exported.map(c => affectedCell(c.id, item))));
}

/** YYYY-MM-DD in local time. */
function localDate(now: Date): string {
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

/** "misconfigurations-2026-11-19.csv". */
export const misconfigurationsFileName = (now: Date): string => csvFileName(`misconfigurations-${localDate(now)}`);

/** "misconfiguration-<policyId>-affected-datastores-2026-11-19.csv". */
export const affectedFileName = (policyId: string, now: Date): string =>
  csvFileName(`misconfiguration-${policyId}-affected-datastores-${localDate(now)}`);

// ─── Affected Datastores filters ─────────────────────────────────────────────

/** The Affected Datastores table's one filter. */
export const ACCOUNT = 'account';

/** The account labels of the affected datastores (A to Z), each with its datastore count. */
export function affectedAccountOptions(affected: readonly AffectedDatastore[]): DatastoreFilterOption[] {
  const counts = new Map<string, number>();
  for (const item of affected) {
    const account = item.datastore.source.accountLabel;
    if (account !== null) counts.set(account, (counts.get(account) ?? 0) + 1);
  }
  return [...counts].map(([value, count]) => ({ value, label: value, count })).sort(byLabel);
}

/** The affected datastores whose name contains the search and whose account is selected, in order. */
export function filterAffected(
  affected: readonly AffectedDatastore[], search: string, filters: DatastoreFilterState,
): AffectedDatastore[] {
  const needle = search.trim().toLowerCase();
  return affected.filter(item => (needle === '' || (item.datastore.name ?? '').toLowerCase().includes(needle))
    && matchesFilters(filters, dimension => {
      const account = item.datastore.source.accountLabel;
      return dimension === ACCOUNT && account !== null ? [account] : [];
    }));
}

const SOURCE_ID = /^[0-9a-fA-F]{24}$/;

/** Whether the row can open the datastore drawer: a 24-hex source id, as on the Datastores page. */
export const isOpenableDatastore = (row: DatastoreRow): row is DrawerRow =>
  row.sourceId !== null && SOURCE_ID.test(row.sourceId);
