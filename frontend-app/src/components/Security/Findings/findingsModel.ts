/**
 * The Security Center Findings tab's pure logic, modelled on the XamLens Misconfigurations page
 * (`components/Dspm/misconfigurations` on main): findings grouped into misconfigurations, filter options
 * and filtering, Group By, the analytics figures, and the CSV of both tables. Everything runs in the
 * browser over the flat list `/api/xamops/security/findings` already sent.
 *
 * Filters reuse the Datastores filter state (`DatastoreFilterState`) in local mode: Include only, values
 * never sent anywhere. A row matches a dimension when any of its values is selected; dimensions combine
 * with AND.
 */
import type { DatastoreFilterDimension, DatastoreFilterOption, DatastoreFilterOptions } from '../../../types/dspmDatastores';
import type { SecurityFinding } from '../../../types/security';
import { csvFileName, toCsv, type CsvCell } from '../../../utils/csvExport';
import type { DatastoreFilterState } from '../../Dspm/datastores/datastoreFilters';

// ─── Severity ────────────────────────────────────────────────────────────────

const SEVERITY_RANK: Readonly<Record<string, number>> = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1 };

/** The four levels, highest first, as the analytics card counts them. */
export const SEVERITY_LEVELS = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as const;
export type SeverityLevelCode = (typeof SEVERITY_LEVELS)[number];

/** `CRITICAL` for "Critical": the scan checks send title case, other sources upper case. Null when blank. */
export function severityCode(severity: string | null | undefined): string | null {
  const code = (severity ?? '').trim().toUpperCase();
  return code === '' ? null : code;
}

/** Critical 4, High 3, Medium 2, Low 1, anything else 0. */
export const severityRank = (code: string | null): number => SEVERITY_RANK[code ?? ''] ?? 0;

/** "Critical" for `CRITICAL`. */
export const severityName = (code: string): string => code.charAt(0) + code.slice(1).toLowerCase();

// ─── Finding text ────────────────────────────────────────────────────────────

/*
 * Live findings fold title, description and remediation into the one description field, as
 * `title — description Remediation: remediation` with the last two parts optional
 * (`SecurityFindingProjectionService.descriptionFor`). The scan checks send one sentence with neither
 * separator. These are the separators that method writes.
 */
const TITLE_SEPARATOR = ' \u2014 ';
const REMEDIATION_MARKER = ' Remediation: ';

export interface FindingText {
  /** The part before the separator, or the whole text without one. Empty for a blank description. */
  title: string;
  description: string | null;
  remediation: string | null;
}

/** Splits a finding's description into the title, description and remediation it was built from. */
export function parseFindingText(text: string | null | undefined): FindingText {
  const raw = (text ?? '').trim();
  const marker = raw.indexOf(REMEDIATION_MARKER);
  const body = marker === -1 ? raw : raw.slice(0, marker).trim();
  const remediation = marker === -1 ? null : raw.slice(marker + REMEDIATION_MARKER.length).trim() || null;
  const separator = body.indexOf(TITLE_SEPARATOR);
  if (separator === -1) return { title: body, description: null, remediation };
  return {
    title: body.slice(0, separator).trim(),
    description: body.slice(separator + TITLE_SEPARATOR.length).trim() || null,
    remediation,
  };
}

// ─── Misconfigurations ───────────────────────────────────────────────────────

/** One check across the resources it fails on: the Security Center's equivalent of a XamLens policy. */
export interface SecurityMisconfiguration {
  /** Severity, framework, control, category and text: rows merge only when all of them agree. */
  key: string;
  title: string;
  description: string | null;
  remediation: string | null;
  /** Upper-cased (`CRITICAL`, `HIGH`…), null when blank. */
  severity: string | null;
  severityLabel: string | null;
  /** The finding's category: the AWS service of a scan check (IAM, S3, VPC), the resource type of a live one. */
  category: string | null;
  framework: string | null;
  controlId: string | null;
  /**
   * One finding per affected resource (id and region), first seen first. Its length is the Affected
   * Resources figure. A resource the check reports twice, such as a TCP and a UDP rule on the same port,
   * appears once.
   */
  findings: SecurityFinding[];
  /** Distinct regions of the affected resources, A to Z. */
  regions: string[];
}

const blankToNull = (value: string | null | undefined): string | null => {
  const trimmed = (value ?? '').trim();
  return trimmed === '' ? null : trimmed;
};

const byText = (a: string, b: string): number =>
  a.toLowerCase().localeCompare(b.toLowerCase()) || a.localeCompare(b);

/**
 * A resource's identity: its id and region. The scan names resources rather than giving ARNs, and some
 * names repeat across regions (an RDS instance identifier, a trail name), so the id alone is not enough.
 */
export const resourceKey = (finding: Pick<SecurityFinding, 'resourceId' | 'region'>): string =>
  JSON.stringify([finding.resourceId ?? '', blankToNull(finding.region) ?? '']);

/** The findings on the same resource as `resource`, in order. */
export function findingsOnResource(
  findings: readonly SecurityFinding[], resource: Pick<SecurityFinding, 'resourceId' | 'region'>,
): SecurityFinding[] {
  const key = resourceKey(resource);
  return findings.filter(finding => resourceKey(finding) === key);
}

function regionsOf(findings: readonly SecurityFinding[]): string[] {
  const regions = new Set<string>();
  for (const finding of findings) {
    const region = blankToNull(finding.region);
    if (region !== null) regions.add(region);
  }
  return [...regions].sort(byText);
}

/**
 * The findings as misconfigurations. Findings of the same check (same severity, framework, control,
 * category and text) become one row whose affected resources are theirs; a resource (id and region) the
 * check reports twice counts once. Rows come highest severity first, then most affected resources, then
 * title A to Z.
 */
export function groupFindings(findings: readonly SecurityFinding[]): SecurityMisconfiguration[] {
  const groups = new Map<string, { row: SecurityMisconfiguration; resources: Set<string> }>();
  for (const finding of findings) {
    const text = parseFindingText(finding.description);
    const severity = severityCode(finding.severity);
    const category = blankToNull(finding.category);
    const framework = blankToNull(finding.complianceFramework);
    const controlId = blankToNull(finding.controlId);
    const key = JSON.stringify([severity, framework, controlId, category, text.title, text.description]);
    let group = groups.get(key);
    if (!group) {
      group = {
        row: {
          key, title: text.title, description: text.description, remediation: text.remediation,
          severity, severityLabel: severity === null ? null : severityName(severity),
          category, framework, controlId, findings: [], regions: [],
        },
        resources: new Set<string>(),
      };
      groups.set(key, group);
    }
    const resource = resourceKey(finding);
    if (group.resources.has(resource)) continue;
    group.resources.add(resource);
    group.row.findings.push(finding);
    if (group.row.remediation === null) group.row.remediation = text.remediation;
  }
  const out = [...groups.values()].map(({ row }) => ({ ...row, regions: regionsOf(row.findings) }));
  return out.sort((a, b) => severityRank(b.severity) - severityRank(a.severity)
    || b.findings.length - a.findings.length
    || byText(a.title, b.title)
    || (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));
}

/** "CIS AWS Foundations 1.2": framework and control, as the old compliance filter named them. */
export function frameworkLabel(row: Pick<SecurityMisconfiguration, 'framework' | 'controlId'>): string | null {
  const label = [row.framework, row.controlId].filter(Boolean).join(' ');
  return label === '' ? null : label;
}

// ─── Analytics ───────────────────────────────────────────────────────────────

export interface FindingsSummary {
  /** Distinct resources (id and region) across the rows: a resource failing three checks counts once. */
  affectedResources: number;
  misconfigurations: number;
  /** Resource and check pairs, the figure the page used to call Total Findings. */
  findings: number;
  /** Misconfigurations per level; a level outside the four counts in `misconfigurations` only. */
  bySeverity: Record<SeverityLevelCode, number>;
}

/** The two analytics cards' figures over the given rows. */
export function summarize(rows: readonly SecurityMisconfiguration[]): FindingsSummary {
  const resources = new Set<string>();
  const bySeverity: Record<SeverityLevelCode, number> = { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0 };
  let findings = 0;
  for (const row of rows) {
    for (const finding of row.findings) {
      if (finding.resourceId) resources.add(resourceKey(finding));
    }
    findings += row.findings.length;
    if ((SEVERITY_LEVELS as readonly (string | null)[]).includes(row.severity)) {
      bySeverity[row.severity as SeverityLevelCode] += 1;
    }
  }
  return { affectedResources: resources.size, misconfigurations: rows.length, findings, bySeverity };
}

// ─── Filter dimensions ───────────────────────────────────────────────────────

export const SEVERITY = 'severity';
export const CATEGORY = 'category';
export const FRAMEWORK = 'framework';
export const REGION = 'region';

/** The "+ More filters" dimensions, in panel order. */
export const MORE_FILTER_DIMENSIONS: readonly string[] = [CATEGORY, FRAMEWORK, REGION];

const DIMENSION_LABELS: Readonly<Record<string, string>> = {
  [SEVERITY]: 'Severity', [CATEGORY]: 'Category', [FRAMEWORK]: 'Framework', [REGION]: 'Region',
};

/** A dimension's name in the filter controls. */
export const dimensionLabel = (dimension: string): string => DIMENSION_LABELS[dimension] ?? dimension;

/** A row's values for one dimension. */
function valuesOf(row: SecurityMisconfiguration, dimension: string): readonly string[] {
  switch (dimension) {
    case SEVERITY: return row.severity === null ? [] : [row.severity];
    case CATEGORY: return row.category === null ? [] : [row.category];
    case FRAMEWORK: return row.framework === null ? [] : [row.framework];
    case REGION: return row.regions;
    default: return [];
  }
}

const optionByLabel = (a: DatastoreFilterOption, b: DatastoreFilterOption) => byText(a.label, b.label);

/** Each value of the dimension with the number of rows carrying it. */
function dimensionOf(rows: readonly SecurityMisconfiguration[], dimension: string): DatastoreFilterDimension {
  const found = new Map<string, DatastoreFilterOption>();
  for (const row of rows) {
    for (const value of new Set(valuesOf(row, dimension))) {
      const option = found.get(value);
      if (option) {
        option.count = (option.count ?? 0) + 1;
        continue;
      }
      found.set(value, { value, label: dimension === SEVERITY ? severityName(value) : value, count: 1 });
    }
  }
  const options = [...found.values()];
  options.sort(dimension === SEVERITY
    ? (a, b) => severityRank(b.value) - severityRank(a.value) || optionByLabel(a, b)
    : optionByLabel);
  return { key: dimension, options, hasMore: false };
}

/** Severity (highest first) and the More-filters dimensions (A to Z), each value counting its rows. */
export function findingFilterOptions(rows: readonly SecurityMisconfiguration[]): DatastoreFilterOptions {
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

const matchesSearch = (row: SecurityMisconfiguration, needle: string): boolean =>
  row.title.toLowerCase().includes(needle)
  || (row.controlId ?? '').toLowerCase().includes(needle)
  || row.findings.some(finding => (finding.resourceId ?? '').toLowerCase().includes(needle));

/**
 * The rows that match the search and every filter, in row order. The search is case-insensitive over
 * the title, the control id and the affected resource ids, so a resource name finds every check it fails.
 */
export function filterMisconfigurations(
  rows: readonly SecurityMisconfiguration[], search: string, filters: DatastoreFilterState,
): SecurityMisconfiguration[] {
  const needle = search.trim().toLowerCase();
  return rows.filter(row => (needle === '' || matchesSearch(row, needle))
    && matchesFilters(filters, dimension => valuesOf(row, dimension)));
}

// ─── Group By ────────────────────────────────────────────────────────────────

export type GroupBy = 'none' | 'severity' | 'framework';

export const GROUP_BY_OPTIONS: ReadonlyArray<{ value: GroupBy; label: string }> = [
  { value: 'none', label: 'Select group by' },
  { value: 'severity', label: 'Severity' },
  { value: 'framework', label: 'Frameworks' },
];

/** One table line: a row, and the group it is drawn under (null when not grouped). */
export interface GroupedItem {
  row: SecurityMisconfiguration;
  groupKey: string | null;
  groupLabel: string | null;
}

/** The group of rows without a framework, drawn last. */
export const NO_FRAMEWORK = '-';

/**
 * The table's lines. Ungrouped: the rows as they are. By severity: highest first, rows in order within
 * each. By framework: frameworks A to Z, rows without one last under "-".
 */
export function groupMisconfigurations(rows: readonly SecurityMisconfiguration[], groupBy: GroupBy): GroupedItem[] {
  if (groupBy === 'none') return rows.map(row => ({ row, groupKey: null, groupLabel: null }));
  if (groupBy === 'severity') {
    return [...rows]
      .sort((a, b) => severityRank(b.severity) - severityRank(a.severity))
      .map(row => ({ row, groupKey: row.severity ?? '', groupLabel: row.severityLabel ?? '-' }));
  }
  const frameworks = [...new Set(rows.flatMap(row => (row.framework === null ? [] : [row.framework])))].sort(byText);
  const items: GroupedItem[] = [];
  for (const framework of frameworks) {
    for (const row of rows) {
      if (row.framework === framework) items.push({ row, groupKey: framework, groupLabel: framework });
    }
  }
  for (const row of rows) {
    if (row.framework === null) items.push({ row, groupKey: NO_FRAMEWORK, groupLabel: NO_FRAMEWORK });
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

export type FindingColumnId = 'misconfiguration' | 'severity' | 'affected' | 'category' | 'regions' | 'frameworks';

/** Table order; Misconfiguration is always shown. */
export const FINDING_COLUMNS: ReadonlyArray<{ id: FindingColumnId; label: string }> = [
  { id: 'misconfiguration', label: 'Misconfiguration' },
  { id: 'severity', label: 'Severity' },
  { id: 'affected', label: 'Affected Resources' },
  { id: 'category', label: 'Category' },
  { id: 'regions', label: 'Regions' },
  { id: 'frameworks', label: 'Frameworks' },
];

function findingCell(id: FindingColumnId, row: SecurityMisconfiguration): CsvCell {
  switch (id) {
    case 'misconfiguration': return row.title;
    case 'severity': return row.severityLabel;
    case 'affected': return row.findings.length;
    case 'category': return row.category;
    case 'regions': return row.regions.join(', ');
    case 'frameworks': return frameworkLabel(row);
  }
}

/** One line per row, in the order given, with the given columns; headers are the column labels. */
export function findingsCsv(
  rows: readonly SecurityMisconfiguration[], columns: ReadonlyArray<{ id: FindingColumnId; label: string }>,
): string {
  return toCsv(columns.map(c => c.label), rows.map(row => columns.map(c => findingCell(c.id, row))));
}

export type AffectedColumnId = 'resource' | 'region' | 'actions';

/** Table order; Affected Resource is always shown. */
export const AFFECTED_COLUMNS: ReadonlyArray<{ id: AffectedColumnId; label: string }> = [
  { id: 'resource', label: 'Affected Resource' },
  { id: 'region', label: 'Region' },
  { id: 'actions', label: 'Actions' },
];

/** The affected resources as CSV with the given columns, except Actions (a button, not data). */
export function affectedCsv(
  findings: readonly SecurityFinding[], columns: ReadonlyArray<{ id: AffectedColumnId; label: string }>,
): string {
  const exported = columns.filter(c => c.id !== 'actions');
  return toCsv(
    exported.map(c => c.label),
    findings.map(finding => exported.map(c => (c.id === 'resource' ? finding.resourceId : finding.region))),
  );
}

/** YYYY-MM-DD in local time. */
function localDate(now: Date): string {
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

/** "security-misconfigurations-2026-11-20.csv". */
export const findingsFileName = (now: Date): string => csvFileName(`security-misconfigurations-${localDate(now)}`);

/** "security-misconfiguration-cis-aws-foundations-1.2-affected-resources-2026-11-20.csv". */
export function affectedFileName(row: SecurityMisconfiguration, now: Date): string {
  const subject = frameworkLabel(row) ?? (row.title.slice(0, 48) || 'finding');
  return csvFileName(`security-misconfiguration-${subject}-affected-resources-${localDate(now)}`);
}

// ─── Affected Resources filter ───────────────────────────────────────────────

/** The regions of the affected resources (A to Z), each with its resource count. */
export function affectedRegionOptions(findings: readonly SecurityFinding[]): DatastoreFilterOption[] {
  const counts = new Map<string, number>();
  for (const finding of findings) {
    const region = blankToNull(finding.region);
    if (region !== null) counts.set(region, (counts.get(region) ?? 0) + 1);
  }
  return [...counts].map(([value, count]) => ({ value, label: value, count })).sort(optionByLabel);
}

/** The affected resources whose id contains the search and whose region is selected, in order. */
export function filterAffected(
  findings: readonly SecurityFinding[], search: string, filters: DatastoreFilterState,
): SecurityFinding[] {
  const needle = search.trim().toLowerCase();
  return findings.filter(finding => (needle === '' || (finding.resourceId ?? '').toLowerCase().includes(needle))
    && matchesFilters(filters, dimension => {
      const region = blankToNull(finding.region);
      return dimension === REGION && region !== null ? [region] : [];
    }));
}
