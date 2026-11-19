/**
 * XamLens Endpoint drawer demo data (ROLE_XAMOPS), design 7.2.
 *
 * Answers `/api/dspm/datastores/{sourceId}/endpoint/*` for every endpoint demo row in the shapes of
 * `types/dspmDrawers.ts`, derived from the device specs in `xamlensMockData.ts`: the entity metrics sum to
 * the row's record count (FR-DEMO-3), document classes come from the file names by keyword, occurrence
 * samples arrive masked as the vendor masks them, and File View pages run over the device's every file
 * (the spec files, then the spec's clean filler).
 *
 * Showcases: Priya's MacBook Pro (`demo-ep-01`: entities, document classes, masked samples, a completed
 * scan), HR-DESKTOP-02 (`demo-ep-05`, Windows: no findings, an in-progress and a timed-out run) and the
 * offline agents (`demo-ep-03`, `demo-ep-06`), whose utilization reads "-".
 */

import type { DatastoreFilterDimension, DatastoreFilterOptions } from '../types/dspmDatastores';
import type {
  EndpointFilePage, EndpointFileRow, EndpointMetric, EndpointOccurrencePage, EndpointOverview, EndpointScan,
  EndpointScanPage, EndpointSensitiveMetrics, EndpointSummary,
} from '../types/dspmDrawers';
import { demoEndpointSource, type DemoEndpointSource, type DemoSourceFile } from './xamlensMockData';

const DAY = 24 * 60 * 60;
const daysAgo = (days: number) => Math.floor(Date.now() / 1000) - Math.round(days * DAY);

/** File name keyword → the vendor's lower-case category and document class, tried in order. */
const DOCUMENT_CLASSES: ReadonlyArray<[keywords: string[], category: string, documentClass: string]> = [
  [['payroll'], 'hr & legal', 'payroll records'],
  [['export', 'leads'], 'marketing & sales', 'customer lists'],
  [['credentials', 'config', '.env'], 'it & technical', 'system documentation'],
];

type DocumentClass = { category: string; documentClass: string };

function documentClassOf(file: DemoSourceFile): DocumentClass | null {
  const name = file.name.toLowerCase();
  const match = DOCUMENT_CLASSES.find(([keywords]) => keywords.some(keyword => name.includes(keyword)));
  return match ? { category: match[1], documentClass: match[2] } : null;
}

const SENSITIVE_LABEL: Record<string, string> = { PII: 'Personal', SECRETS: 'IT & Security', PCI: 'Financial', PHI: 'Health' };
const RISK_LABEL: Record<string, string> = { CRITICAL: 'Critical', HIGH: 'High', MEDIUM: 'Medium', LOW: 'Low' };

/** `DatastoreLabels.documentCategoryLabel`: "it & technical" → "IT & Technical". */
const documentCategoryLabel = (code: string) => code.split(' ')
  .map(word => (['it', 'hr', 'kyc'].includes(word) ? word.toUpperCase() : word.charAt(0).toUpperCase() + word.slice(1)))
  .join(' ');

/** `DatastoreLabels.titleCase`, the File View's spelling (GAP-51): "it & technical" → "It & Technical". */
const titleCase = (text: string) => text.split(' ').map(word => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase()).join(' ');

/** `DatastoreLabels.KNOWN_UPPER_EXTENSIONS`: the chips written in upper case; any other is capitalised. */
const UPPER_EXTENSIONS = new Set(['java', 'tsx', 'ts', 'sql', 'css', 'yaml', 'sh', 'go', 'md', 'yml', 'py', 'json', 'html',
  'js', 'xml', 'txt', 'conf', 'mjs', 'csv', 'docx', 'mp3', 'pdf']);
const extensionLabel = (extension: string) =>
  UPPER_EXTENSIONS.has(extension) ? extension.toUpperCase() : extension.charAt(0).toUpperCase() + extension.slice(1);

/** The frontend's `extensionOf` rule: the text after the last dot of the basename (".env" → "env"). */
function extensionOfName(name: string): string | null {
  const base = name.slice(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
  const dot = base.lastIndexOf('.');
  return dot < 0 || dot === base.length - 1 ? null : base.slice(dot + 1).toLowerCase();
}

const extensionOf = (file: DemoSourceFile) => file.extension ?? extensionOfName(file.name);

/** A small stable number per text, for invented sizes and times. */
function hashOf(text: string): number {
  let hash = 7;
  for (const char of text) hash = (hash * 31 + char.charCodeAt(0)) % 1_000_003;
  return hash;
}

/** The vendor masks endpoint samples itself; the demo sends them masked the same way. */
const MASKED: Record<string, readonly string[]> = {
  'EMAIL ADDRESS': ['j*******@e******.com', 'r*****@e******.com', 'm****@e******.com'],
  'PERSON NAME': ['J*** D**', 'R****** R**', 'M*** M****'],
  'PHONE NUMBER': ['+1 ***-***-0142', '+1 ***-***-0177', '+1 ***-***-0100'],
  'BANK ACCOUNT NUMBER': ['********4021', '********7765'],
  'PAN NUMBER': ['*****1234*'],
  'ACCESS KEY AWS': ['AKIA************DEMO'],
};

/** Tags on two showcase files (Matters Tags); every other file has none. */
const TAGS: Record<string, string[]> = {
  'customer_export_sept.csv': ['customer-data'],
  'payroll_draft.xlsx': ['hr-restricted'],
};

// ─── Filters ─────────────────────────────────────────────────────────────────

interface Filters { sensitives: string[]; documentClass: string[]; category: string[]; dataElement: string[] }

/** Each filter repeated or comma-joined, trimmed, as `EndpointFilters.parse` reads them. */
function filtersOf(params: URLSearchParams): Filters {
  const values = (name: string) => params.getAll(name).flatMap(raw => raw.split(',')).map(value => value.trim()).filter(Boolean);
  return {
    sensitives: values('sensitives'), documentClass: values('documentClass'),
    category: values('category'), dataElement: values('dataElement'),
  };
}

function fileMatches(file: DemoSourceFile, filters: Filters): boolean {
  const documentClass = documentClassOf(file);
  return (filters.sensitives.length === 0 || file.findings.some(f => f.category !== null && filters.sensitives.includes(f.category)))
    && (filters.dataElement.length === 0 || file.findings.some(f => filters.dataElement.includes(f.entityType)))
    && (filters.documentClass.length === 0 || (documentClass !== null && filters.documentClass.includes(documentClass.documentClass)))
    && (filters.category.length === 0 || (documentClass !== null && filters.category.includes(documentClass.category)));
}

/** The entity rows of the matching files (only the filtered entities when an entity filter is set), then the document classes. */
function metricsOf(source: DemoEndpointSource, filters: Filters): EndpointMetric[] {
  const files = source.files.filter(file => fileMatches(file, filters));
  const entities = new Map<string, EndpointMetric>();
  const classes = new Map<string, EndpointMetric>();
  for (const file of files) {
    for (const finding of file.findings) {
      if (filters.dataElement.length > 0 && !filters.dataElement.includes(finding.entityType)) continue;
      if (filters.sensitives.length > 0 && (finding.category === null || !filters.sensitives.includes(finding.category))) continue;
      const metric = entities.get(finding.entityType)
        ?? { kind: 'ENTITY' as const, name: finding.entityType, categoryLabel: finding.categoryLabel, count: 0 };
      metric.count += finding.count;
      entities.set(finding.entityType, metric);
    }
    const documentClass = documentClassOf(file);
    if (documentClass) {
      const metric = classes.get(documentClass.documentClass) ?? {
        kind: 'DOCUMENT_CLASS' as const, name: documentClass.documentClass,
        categoryLabel: documentCategoryLabel(documentClass.category), count: 0,
      };
      metric.count += 1;
      classes.set(documentClass.documentClass, metric);
    }
  }
  // Count descending; the sort is stable, so ties keep their order, as the backend's.
  return [...entities.values(), ...classes.values()].sort((a, b) => b.count - a.count);
}

function filterOptions(source: DemoEndpointSource): DatastoreFilterOptions {
  const unique = (values: string[]) => [...new Set(values)];
  const findings = source.files.flatMap(file => file.findings);
  const classes = source.files.map(documentClassOf).filter((c): c is DocumentClass => c !== null);
  const dimension = (key: string, values: string[], label: (value: string) => string): DatastoreFilterDimension => ({
    key, options: values.map(value => ({ value, label: label(value), count: null })), hasMore: false,
  });
  return {
    dimensions: [
      dimension('sensitives', unique(findings.map(f => f.category).filter((c): c is string => c !== null)), code => SENSITIVE_LABEL[code] ?? code),
      dimension('documentClass', unique(classes.map(c => c.documentClass)), value => value),
      dimension('category', unique(classes.map(c => c.category)), documentCategoryLabel),
      dimension('dataElement', unique(findings.map(f => f.entityType)), value => value),
    ],
  };
}

// ─── Information, files and scans ────────────────────────────────────────────

const scanned = (source: DemoEndpointSource) => source.status === 'CLASSIFIED';
/** The completed scan's end: the strip's Last Scan. */
const lastScanOf = (source: DemoEndpointSource) => (scanned(source) ? daysAgo(1 + (hashOf(source.id) % 5) / 4) : null);

function overview(source: DemoEndpointSource): EndpointOverview {
  const { facet } = source;
  const text = (key: string) => (typeof facet[key] === 'string' ? facet[key] as string : null);
  const number = (key: string) => (typeof facet[key] === 'number' ? facet[key] as number : null);
  const deviceType = text('device_type');
  return {
    serialNumber: text('serial_number'),
    lastScanAt: lastScanOf(source),
    lastAccessAt: number('last_ping_time'),
    severityLabel: RISK_LABEL[source.riskLevel] ?? null,
    agentStatusLabel: source.online ? 'Online' : 'Offline',
    agentOnline: source.online,
    users: [source.user],
    hostname: text('hostname'),
    visibleIp: text('public_ip'),
    macAddress: text('mac_address'),
    deviceTypeLabel: deviceType ? deviceType.charAt(0).toUpperCase() + deviceType.slice(1) : null,
    osVersion: text('os_version'),
    architecture: text('architecture'),
    memory: text('memory'),
    cpuUsage: number('cpu_usage'),
    memoryUsage: number('memory_usage'),
    agentVersion: text('agent_version'),
  };
}

/** Every file on the device: the spec files by records descending, then the clean filler. */
function allFiles(source: DemoEndpointSource): DemoSourceFile[] {
  const files = [...source.files].sort((a, b) => b.records - a.records);
  for (let i = 0; files.length < source.objectTotal; i++) files.push(source.filler(i));
  return files;
}

function summary(source: DemoEndpointSource): EndpointSummary {
  const extensions = new Map<string, number>();
  for (const file of allFiles(source)) {
    const extension = extensionOf(file);
    if (extension) extensions.set(extension, (extensions.get(extension) ?? 0) + 1);
  }
  const labelGroups = new Map<string, Map<string, number>>();
  const categories = new Map<string, Map<string, number>>();
  for (const file of source.files) {
    for (const finding of file.findings) {
      const group = labelGroups.get(finding.categoryLabel ?? '-') ?? new Map<string, number>();
      group.set(finding.entityType, (group.get(finding.entityType) ?? 0) + finding.count);
      labelGroups.set(finding.categoryLabel ?? '-', group);
    }
    const documentClass = documentClassOf(file);
    if (documentClass) {
      const group = categories.get(documentClass.category) ?? new Map<string, number>();
      group.set(documentClass.documentClass, (group.get(documentClass.documentClass) ?? 0) + 1);
      categories.set(documentClass.category, group);
    }
  }
  const counts = (group: Map<string, number>) => [...group].map(([name, count]) => ({ name, count }));
  const known = source.status !== 'NOT_SCANNED';
  return {
    totalFiles: known ? source.objectTotal : null,
    extensions: [...extensions]
      .map(([extension, count]) => ({ extension, label: extensionLabel(extension), count }))
      .sort((a, b) => b.count - a.count || a.extension.localeCompare(b.extension)),
    totalSensitives: known ? source.recordCount : null,
    labelGroups: [...labelGroups].map(([label, group]) => ({ label, entities: counts(group) })),
    fileCategories: [...categories].map(([category, group]) => ({ label: documentCategoryLabel(category), documentClasses: counts(group) })),
  };
}

function fileRow(source: DemoEndpointSource, file: DemoSourceFile): EndpointFileRow {
  const documentClass = documentClassOf(file);
  const hash = hashOf(file.path ?? file.name);
  return {
    name: file.name,
    extension: extensionOf(file),
    path: file.path,
    user: source.user,
    sizeBytes: 512 + (hash % 900_000),
    sensitiveCount: file.records,
    labels: [...new Set(file.findings.map(f => f.categoryLabel).filter((l): l is string => l !== null))],
    categoryLabel: documentClass ? titleCase(documentClass.category) : null,
    documentClassLabel: documentClass ? titleCase(documentClass.documentClass) : null,
    tags: TAGS[file.name] ?? [],
    modifiedAt: daysAgo(2 + (hash % 90)),
  };
}

const intParam = (params: URLSearchParams, name: string, fallback: number, min: number, max: number) =>
  Math.min(max, Math.max(min, Number.parseInt(params.get(name) ?? '', 10) || fallback));

function scans(source: DemoEndpointSource): EndpointScan[] {
  const sensitiveFiles = source.files.filter(file => file.records > 0).length;
  const lastScan = lastScanOf(source);
  if (lastScan !== null) {
    return [{
      startTime: lastScan - 5 * 3600, endTime: lastScan, status: 'COMPLETED', statusLabel: 'Completed',
      sensitiveFiles, totalFiles: source.objectTotal,
    }];
  }
  if (source.status !== 'SCANNING') return [];
  // The running scan has no end and no total yet (GAP-50); the run before it timed out.
  return [
    { startTime: daysAgo(0.1), endTime: null, status: 'IN_PROGRESS', statusLabel: 'In Progress', sensitiveFiles: 0, totalFiles: null },
    { startTime: daysAgo(3), endTime: daysAgo(2.75), status: 'TIMED_OUT', statusLabel: 'Timed Out', sensitiveFiles: 0, totalFiles: 1_240 },
  ];
}

/**
 * `GET /api/dspm/datastores/{sourceId}/endpoint/{resource}` for an endpoint row, or null for an id that
 * is not an endpoint row (the router's 404). `url` carries the filters, search, entity name and paging.
 */
export function demoEndpointResource(id: string, resource: string, url: string): object | null {
  const source = demoEndpointSource(id);
  if (!source) return null;
  const params = new URL(url, 'http://localhost').searchParams;
  const skip = intParam(params, 'skip', 0, 0, 10_000);
  const limit = intParam(params, 'limit', 10, 1, 100);
  switch (resource) {
    case 'overview': return overview(source);
    case 'filter-options': return filterOptions(source);
    case 'summary': return summary(source);
    case 'sensitives': return { metrics: metricsOf(source, filtersOf(params)) } satisfies EndpointSensitiveMetrics;
    case 'occurrences': {
      const entityName = (params.get('entityName') ?? '').trim();
      const rows = source.files
        .filter(file => file.findings.some(f => f.entityType === entityName))
        .sort((a, b) => b.records - a.records)
        .map(file => {
          const count = file.findings.find(f => f.entityType === entityName)!.count;
          const pool = MASKED[entityName] ?? ['*****'];
          return {
            fileName: file.name, path: file.path, tags: TAGS[file.name] ?? [], falsePositive: false,
            samples: pool.slice(0, Math.min(count, pool.length)),
          };
        });
      return { entityName, total: rows.length, skip, limit, rows: rows.slice(skip, skip + limit) } satisfies EndpointOccurrencePage;
    }
    case 'files': {
      const filters = filtersOf(params);
      const q = (params.get('q') ?? '').trim().toLowerCase();
      const files = allFiles(source).filter(file => fileMatches(file, filters) && (!q || file.name.toLowerCase().includes(q)));
      return {
        rows: files.slice(skip, skip + limit).map(file => fileRow(source, file)), skip, limit, total: files.length,
      } satisfies EndpointFilePage;
    }
    case 'scans': {
      const runs = scans(source);
      return { rows: runs.slice(skip, skip + limit), skip, limit, total: runs.length } satisfies EndpointScanPage;
    }
    default: return null;
  }
}
