/**
 * XamLens Files page demo data (ROLE_XAMOPS), design 7.2 as changed by plan C5 to C9, C17 to C20.
 *
 * Answers `/api/dspm/files/**` and `/api/dspm/endpoint-files/{fileId}/**` in the shapes of
 * `types/dspmFiles.ts`. Every value comes from build 2's drawer demo through its accessors, so a row and its
 * drawer agree by construction: a SaaS row is read from the SaaS file drawer's overview, and an endpoint row is
 * the row File View lists for its device. Times are fixed when the modules load.
 */

import type { DatastoreFilterDimension, DatastoreFilterOptions } from '../types/dspmDatastores';
import type { EndpointFilePage, SaasFileCopies, SaasFileLineage, SaasFileOverview } from '../types/dspmDrawers';
import type {
  EndpointFileActivity, EndpointFileLineage, EndpointFileOverview, SaasFileListPage, SaasFileListRow, SharingMode,
} from '../types/dspmFiles';
import {
  DEMO_COVERAGE_NOTE, demoMacOfferLetterCopies, demoSaasDocumentClasses, demoSaasFileIds, demoSaasFileOverview,
  demoSaasFileSourceId,
} from './xamlensSaasDrawerMockData';
import {
  DEMO_ENDPOINT_TAGS, DEMO_NOW, demoEndpointDocumentClass, demoEndpointFileEntries, demoEndpointFileEntry,
  demoEndpointFileMatches, demoEndpointSourceIds, type DemoEndpointFileEntry,
} from './xamlensEndpointDrawerMockData';
import { demoEndpointSource } from './xamlensMockData';

const DAY = 24 * 60 * 60;

/** The Sensitives filter on both tabs (plan C1): the console's static list and `DspmLabels` labels. */
const SENSITIVES: ReadonlyArray<[code: string, label: string]> = [
  ['PII', 'Personal'], ['PCI', 'Financial'], ['PHI', 'Health'], ['SECRETS', 'IT & Security'],
];

/** The App Type filter (plan C5): the console's fixed nine with its labels. */
const APP_TYPES: ReadonlyArray<[code: string, label: string]> = [
  ['ONE_DRIVE', 'OneDrive'], ['SHARE_POINT', 'SharePoint'], ['OUTLOOK', 'Outlook'], ['GOOGLE_WORKSPACE', 'Google Drive'],
  ['GMAIL', 'Gmail'], ['JIRA', 'Jira'], ['GITLAB', 'Gitlab'], ['CONFLUENCE', 'Confluence'], ['SLACK', 'Slack'],
];
/** `DatastoreLabels.appTypeLabel` for the demo's two drives. */
const SOURCE_LABEL: Record<string, string> = { GMAIL: 'Gmail', GOOGLE_WORKSPACE: 'Google Drive' };

const SHARING_FILTERS: ReadonlyArray<[value: string, label: string]> = [
  ['internal', 'Shared Internally'], ['external', 'Shared Externally'], ['public', 'Shared Publicly'],
];
const SHARING_MODE: Record<string, string> = {
  PUBLIC: 'Anyone with the link', EXTERNAL: 'Externally Shared', INTERNAL: 'Internally Shared', PRIVATE: 'Private',
};
/** The Sharing Mode filter value → the mode code it selects. */
const SHARING_CODE: Record<string, string> = { internal: 'INTERNAL', external: 'EXTERNAL', public: 'PUBLIC' };

const LAST_MODIFIED: ReadonlyArray<[value: string, label: string, seconds: number]> = [
  ['48h', 'Last 48 hours', 2 * DAY], ['7d', 'Last 7 days', 7 * DAY], ['30d', 'Last 30 days', 30 * DAY],
];

const unique = <T,>(values: T[]): T[] => [...new Set(values)];

/** Each filter repeated or comma-joined, trimmed, as the backend reads them. */
const valuesOf = (params: URLSearchParams, name: string) =>
  params.getAll(name).flatMap(raw => raw.split(',')).map(value => value.trim()).filter(Boolean);

const dimension = (key: string, values: ReadonlyArray<readonly [string, string]>): DatastoreFilterDimension => ({
  key, options: values.map(([value, label]) => ({ value, label, count: null })), hasMore: false,
});
const listed = (key: string, values: string[], label: (value: string) => string = value => value) =>
  dimension(key, unique(values).map(value => [value, label(value)] as const));

/** `DatastoreLabels.categoryChipLabel`: the first letter of each word upper-cased, the rest kept. */
const categoryChipLabel = (value: string) => value.split(/\s+/).filter(Boolean)
  .map(word => word.charAt(0).toUpperCase() + word.slice(1)).join(' ');

/** `DatastoreLabels.dataElementOptionLabel`: sentence case per `_` part, "EMAIL ADDRESS" → "Email address". */
const dataElementLabel = (value: string) => value.trim().split('_')
  .map(part => (part ? part.charAt(0).toUpperCase() + part.slice(1).toLowerCase() : part)).join('_');

const intParam = (params: URLSearchParams, name: string, fallback: number, min: number, max: number) =>
  Math.min(max, Math.max(min, Number.parseInt(params.get(name) ?? '', 10) || fallback));

const paramsOf = (url: string) => new URL(url, 'http://localhost').searchParams;

// ─── SaaS tab ────────────────────────────────────────────────────────────────

/** A row with what its filters read and the drawer overview it came from. */
interface SaasEntry {
  row: SaasFileListRow;
  overview: SaasFileOverview;
  /** The datastore's source id: the Owners filter's value. */
  ownerId: string;
  sensitives: string[];
}

/** The modes of a file's sharing (plan C7), in display order; "Private" when none applies. */
function sharingModesOf(sharing: SaasFileOverview['sharing']): SharingMode[] {
  const codes = [
    sharing.publicShare ? 'PUBLIC' : null,
    sharing.externalEmails.length > 0 ? 'EXTERNAL' : null,
    sharing.internalEmails.length > 0 ? 'INTERNAL' : null,
  ].filter((code): code is string => code !== null);
  return (codes.length > 0 ? codes : ['PRIVATE']).map(code => ({ code, label: SHARING_MODE[code] }));
}

function saasEntries(): SaasEntry[] {
  const entries = demoSaasFileIds().map(id => {
    const overview = demoSaasFileOverview(id)!;
    const row: SaasFileListRow = {
      id,
      name: overview.name,
      // A Gmail mail's extension is its subject's tail, which the backend drops (GAP-30).
      extension: overview.fileExtension === 'msg' ? null : overview.fileExtension,
      source: overview.driveType,
      sourceLabel: overview.driveType ? SOURCE_LABEL[overview.driveType] ?? overview.driveType : null,
      categoryLabel: overview.categoryLabel,
      documentClassLabel: overview.documentClassLabel,
      sharingModes: sharingModesOf(overview.sharing),
      owner: overview.owner,
      lastModifiedAt: overview.lastModifiedAt,
    };
    const sensitives = unique(overview.sensitives.map(s => s.categoryLabel)
      .map(label => SENSITIVES.find(([, l]) => l === label)?.[0]).filter((code): code is string => !!code));
    return { row, overview, ownerId: demoSaasFileSourceId(id)!, sensitives };
  });
  // Newest first, then by id, as the console orders them.
  return entries.sort((a, b) => (b.row.lastModifiedAt ?? 0) - (a.row.lastModifiedAt ?? 0) || a.row.id!.localeCompare(b.row.id!));
}

/** The base64url demo cursor of an offset. */
const cursorOf = (offset: number) => btoa(JSON.stringify({ o: offset })).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

/** The offset of a demo cursor; 0 (the first page) for none or an unreadable one. */
function offsetOf(cursor: string | null): number {
  if (!cursor) return 0;
  try {
    const offset = JSON.parse(atob(cursor.replace(/-/g, '+').replace(/_/g, '/'))).o;
    return Number.isInteger(offset) && offset >= 0 ? offset : 0;
  } catch {
    return 0;
  }
}

/** `GET /api/dspm/files/saas`: the filtered rows, one keyset page. */
export function demoSaasFiles(url: string): SaasFileListPage {
  const params = paramsOf(url);
  const q = (params.get('q') ?? '').trim().toLowerCase();
  const sensitives = valuesOf(params, 'sensitives');
  const documentClass = (params.get('documentClass') ?? '').trim();
  const categories = valuesOf(params, 'category');
  const appTypes = valuesOf(params, 'appType');
  const owners = valuesOf(params, 'owner').map(value => value.toLowerCase());
  const internalUsers = valuesOf(params, 'internalUser').map(value => value.toLowerCase());
  const externalUsers = valuesOf(params, 'externalUser').map(value => value.toLowerCase());
  const sharing = SHARING_CODE[(params.get('sharing') ?? '').trim()] ?? null;
  const anyOf = (wanted: string[], have: string[]) => wanted.length === 0 || have.some(value => wanted.includes(value.toLowerCase()));

  const rows = saasEntries().filter(({ row, overview, ownerId, sensitives: own }) =>
    (!q || (row.name ?? '').toLowerCase().includes(q))
    && (sensitives.length === 0 || own.some(code => sensitives.includes(code)))
    && (!documentClass || row.documentClassLabel === documentClass)
    && (categories.length === 0 || (row.categoryLabel !== null && categories.includes(row.categoryLabel)))
    && (appTypes.length === 0 || (row.source !== null && appTypes.includes(row.source)))
    && (owners.length === 0 || owners.includes(ownerId))
    && anyOf(internalUsers, overview.sharing.internalEmails)
    && anyOf(externalUsers, overview.sharing.externalEmails)
    && (!sharing || row.sharingModes.some(mode => mode.code === sharing)))
    .map(entry => entry.row);

  const limit = intParam(params, 'limit', 10, 1, 100);
  const offset = offsetOf(params.get('cursor'));
  const next = offset + limit;
  return { rows: rows.slice(offset, next), limit, nextCursor: next < rows.length ? cursorOf(next) : null };
}

/**
 * `GET /api/dspm/files/saas/filter-options`, in the More filters order. A `category` selection narrows
 * Document Class to its classes.
 */
export function demoSaasFileOptions(url: string): DatastoreFilterOptions {
  const entries = saasEntries();
  const selected = valuesOf(paramsOf(url), 'category');
  const classes = [
    ...demoSaasDocumentClasses(),
    ...entries.filter(e => e.row.categoryLabel && e.row.documentClassLabel)
      .map(e => ({ category: e.row.categoryLabel!, documentClass: e.row.documentClassLabel! })),
  ];
  // One owner per SaaS datastore with files, in spec order (so Priya's Gmail and Drive give two).
  const owners = new Map<string, string>();
  for (const id of demoSaasFileIds()) {
    const ownerId = demoSaasFileSourceId(id)!;
    if (!owners.has(ownerId)) owners.set(ownerId, demoSaasFileOverview(id)!.owner ?? ownerId);
  }
  const byId = [...entries].sort((a, b) => a.row.id!.localeCompare(b.row.id!));
  return {
    dimensions: [
      dimension('sensitives', SENSITIVES),
      listed('category', classes.map(c => c.category), categoryChipLabel),
      dimension('appType', APP_TYPES),
      listed('documentClass', classes.filter(c => selected.length === 0 || selected.includes(c.category))
        .map(c => c.documentClass), categoryChipLabel),
      dimension('owner', [...owners]),
      listed('internalUser', byId.flatMap(e => e.overview.sharing.internalEmails)),
      listed('externalUser', byId.flatMap(e => e.overview.sharing.externalEmails)),
      dimension('sharing', SHARING_FILTERS),
    ],
  };
}

// ─── Endpoint tab ────────────────────────────────────────────────────────────

const allEndpointEntries = (): DemoEndpointFileEntry[] => demoEndpointSourceIds().flatMap(demoEndpointFileEntries);

const serialOf = (entry: DemoEndpointFileEntry) => entry.source.facet.serial_number as string;

/** `GET /api/dspm/files/endpoint`: every device's File View rows in seed order, filtered, then `skip` / `limit`. */
export function demoEndpointFiles(url: string): EndpointFilePage {
  const params = paramsOf(url);
  const q = (params.get('q') ?? '').trim().toLowerCase();
  const tags = valuesOf(params, 'tag');
  const uids = valuesOf(params, 'uid');
  const hostnames = valuesOf(params, 'hostname');
  const window = LAST_MODIFIED.find(([value]) => value === (params.get('lastModified') ?? '').trim())?.[2] ?? null;
  const rows = allEndpointEntries().filter(entry =>
    demoEndpointFileMatches(entry.file, params)
    && (!q || entry.file.name.toLowerCase().includes(q))
    && (tags.length === 0 || entry.row.tags.some(tag => tags.includes(tag)))
    && (uids.length === 0 || uids.includes(serialOf(entry)))
    && (hostnames.length === 0 || hostnames.includes(entry.source.name))
    && (window === null || (entry.row.modifiedAt !== null && entry.row.modifiedAt > DEMO_NOW - window)))
    .map(entry => entry.row);
  const skip = intParam(params, 'skip', 0, 0, 10_000);
  const limit = intParam(params, 'limit', 10, 1, 100);
  return { rows: rows.slice(skip, skip + limit), skip, limit, total: rows.length };
}

/** `GET /api/dspm/files/endpoint/filter-options`, in the More filters order. */
export function demoEndpointFileOptions(): DatastoreFilterOptions {
  const devices = demoEndpointSourceIds().map(id => demoEndpointSource(id)!);
  const files = devices.flatMap(device => device.files);
  const classes = files.map(demoEndpointDocumentClass).filter(c => c !== null);
  return {
    dimensions: [
      listed('category', classes.map(c => c.category)),
      dimension('sensitives', SENSITIVES),
      listed('documentClass', classes.map(c => c.documentClass)),
      listed('dataElement', files.flatMap(file => file.findings.map(f => f.entityType)), dataElementLabel),
      listed('tag', Object.values(DEMO_ENDPOINT_TAGS).flat()),
      listed('uid', devices.map(device => device.facet.serial_number as string)),
      listed('hostname', devices.map(device => device.name)),
      dimension('lastModified', LAST_MODIFIED.map(([value, label]) => [value, label] as const)),
    ],
  };
}

// ─── Endpoint file drawer ────────────────────────────────────────────────────

/**
 * The derived File Created and Last Accessed of a file changed at `modifiedAt` (design 7.2): created 30 days
 * before the change, accessed a day after it, clamped to now and never before the change.
 */
export function demoEndpointFileTimes(modifiedAt: number | null, now = DEMO_NOW): { fileCreatedAt: number | null; accessedAt: number | null } {
  if (modifiedAt === null) return { fileCreatedAt: null, accessedAt: null };
  return { fileCreatedAt: modifiedAt - 30 * DAY, accessedAt: Math.min(modifiedAt + DAY, Math.max(modifiedAt, now)) };
}

const isShowcase = (entry: DemoEndpointFileEntry) => entry.source.id === 'demo-ep-01' && entry.file.name === 'offer-letter.docx';

function overviewOf({ source, file, row }: DemoEndpointFileEntry): EndpointFileOverview {
  return {
    name: row.name,
    extension: row.extension,
    owner: source.user,
    categoryLabel: row.categoryLabel,
    documentClassLabel: row.documentClassLabel,
    sensitiveRecords: file.records,
    path: row.path,
    sizeBytes: row.sizeBytes,
    device: source.name,
    modifiedAt: row.modifiedAt,
    ...demoEndpointFileTimes(row.modifiedAt),
    tags: row.tags,
    sensitives: [...file.findings].sort((a, b) => b.count - a.count)
      .map(f => ({ dataElement: f.entityType, categoryLabel: f.categoryLabel, count: f.count })),
  };
}

function activityOf(entry: DemoEndpointFileEntry): EndpointFileActivity {
  if (!isShowcase(entry)) return { entries: [] };
  return {
    entries: [{
      action: 'TAG_APPLIED', actionLabel: 'Tag applied', selectedTag: 'Confidential', previousTag: null,
      performedBy: 'priya@xammer.in', occurredAt: DEMO_NOW - 3 * DAY, keywordsWritten: true, mipLabelWritten: false,
    }],
  };
}

/** The file's own hash-lineage lane, for a file without copies. */
function ownLane({ source, row }: DemoEndpointFileEntry, firstSeenAt: number | null): SaasFileLineage {
  return {
    available: true,
    coverageNote: DEMO_COVERAGE_NOTE,
    lanes: [{
      key: 'copy-0', self: true, fileName: row.name, ownerName: source.name, ownerEmail: null, driveType: null,
      os: source.os, provider: 'ENDPOINT_AGENT', channelLabel: 'Endpoint', firstSeenAt,
    }],
    events: [],
  };
}

function lineageOf(entry: DemoEndpointFileEntry): EndpointFileLineage {
  const { source, row } = entry;
  const firstSeenAt = demoEndpointFileTimes(row.modifiedAt).fileCreatedAt;
  return {
    asset: { title: row.name, device: source.name, osUser: source.user, firstSeenAt, events: [] },
    copies: isShowcase(entry) ? demoMacOfferLetterCopies().lineage : ownLane(entry, firstSeenAt),
  };
}

function copiesOf(entry: DemoEndpointFileEntry): SaasFileCopies {
  return isShowcase(entry) ? demoMacOfferLetterCopies().copies : { available: true, copies: [], locations: 1 };
}

/**
 * `GET /api/dspm/endpoint-files/{fileId}/{resource}` for a file the Endpoint tab lists, or null for an unknown
 * id (the router's 404).
 */
export function demoEndpointFileResource(fileId: string, resource: string): object | null {
  const entry = demoEndpointFileEntry(fileId);
  if (!entry) return null;
  switch (resource) {
    case 'overview': return overviewOf(entry);
    case 'activity': return activityOf(entry);
    case 'lineage': return lineageOf(entry);
    case 'copies': return copiesOf(entry);
    default: return null;
  }
}
