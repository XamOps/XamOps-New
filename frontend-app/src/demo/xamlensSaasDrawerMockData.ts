/**
 * XamLens SaaS drawer and SaaS file drawer demo data (ROLE_XAMOPS), design 7.2.
 *
 * Answers `/api/dspm/datastores/{sourceId}/saas/*` for every SaaS demo row and
 * `/api/dspm/saas-files/{fileId}/*` for every file those rows list, in the shapes of
 * `types/dspmDrawers.ts`. Demo mode returns DTOs, so values are written as the backend sends them after
 * its mappers (labels resolved, `self` set on the opened file's own copy).
 *
 * Everything is derived from the SaaS specs in `xamlensMockData.ts`, so the drawer agrees with the list:
 * the data elements sum to the row's record count (FR-DEMO-3). File ids are 24 hex: `de5`, the spec
 * number in three digits, then the file's index in 18. Owners keep the specs' `@xammer.in` addresses;
 * every address this module invents uses `example.com`.
 *
 * Showcases on Priya Nair's Gmail (`demo-saas-03`) and Google Drive (`demo-saas-04`): a Gmail message
 * with no copies, the Gmail attachment "Offer Letter - Priya Nair.pdf" with activity and two Drive
 * copies, the Drive "Offer Letter - Priya Nair.docx" shared externally with copies in Gmail, Drive and on
 * a MacBook, and "Team Roster.xlsx" shared privately.
 */

import type {
  SaasDrawerHeader, SaasFileActivity, SaasFileCopies, SaasFileCopy, SaasFileLineage, SaasFileLineageEvent,
  SaasFileLineageLane, SaasFileMetadataEntry, SaasFileOverview, SaasFilePage, SaasFileSharing, SaasRecordRow,
  SaasRecords, SaasScanSummary, SaasScanSummaryFileType,
} from '../types/dspmDrawers';
import { demoSaasSource, type DemoSaasSource, type DemoSourceFile } from './xamlensMockData';

const DAY = 24 * 60 * 60;
/** The module's now (epoch seconds), taken once at load, so every time it gives is stable for the page's life. */
const NOW = Math.floor(Date.now() / 1000);
const daysAgo = (days: number) => NOW - Math.round(days * DAY);

const ACCOUNT_NAME = 'xammer-workspace';
const DRIVE_LABEL: Record<DemoSaasSource['drive'], string> = { GMAIL: 'Gmail', GOOGLE_WORKSPACE: 'Google Drive' };
const SCAN_STATE_LABEL: Record<string, string> = {
  COMPLETED: 'Completed', IN_PROGRESS: 'In Progress', NOT_STARTED: 'Not Started', DISABLED: 'Disabled',
};

/** The vendor's coverage note, in our words: the ⓘ of a non-Gmail file's lineage title. */
const COVERAGE_NOTE = 'Copies are linked when their content is identical (the same content hash) across connected '
  + 'sources. Edited copies are not linked.';

/** A request the backend refuses with 400 INVALID_REQUEST: the parameter it names. */
export interface DemoInvalidRequest {
  readonly invalidParameter: 'dataElement' | 'documentClass';
}

/** The sentinels the router answers with `makeDspmError(400, 'INVALID_REQUEST', …)` (design 7.1). */
export const DEMO_INVALID_REQUEST: Readonly<Record<DemoInvalidRequest['invalidParameter'], DemoInvalidRequest>> = {
  dataElement: { invalidParameter: 'dataElement' },
  documentClass: { invalidParameter: 'documentClass' },
};

export const isDemoInvalidRequest = (body: unknown): body is DemoInvalidRequest =>
  body === DEMO_INVALID_REQUEST.dataElement || body === DEMO_INVALID_REQUEST.documentClass;

// ─── Document classes (Gmail only; the Drive accounts have none) ─────────────

/** Subject keyword → category and document class, in the order they are tried. */
const DOCUMENT_CLASSES: ReadonlyArray<[keywords: string[], category: string, documentClass: string]> = [
  [['invoice', 'remittance', 'payment'], 'Financial', 'Invoices & Receipts'],
  [['contract', 'msa', 'offer'], 'Legal', 'General Contracts & Agreements'],
  [['candidate', 'kyc'], 'Identity & Personal', 'KYC Documents'],
  [['escalation', 'on-call', 'incident'], 'IT & Technical', 'Incident Reports'],
];

function documentClassOf(source: DemoSaasSource, file: DemoSourceFile): { category: string; documentClass: string } | null {
  if (source.drive !== 'GMAIL') return null;
  const name = file.name.toLowerCase();
  const match = DOCUMENT_CLASSES.find(([keywords]) => keywords.some(keyword => name.includes(keyword)));
  return match ? { category: match[1], documentClass: match[2] } : null;
}

/** Count descending; `Array.prototype.sort` is stable, so ties keep their first appearance. */
const byCount = <T extends { count: number }>(rows: T[]): T[] => [...rows].sort((a, b) => b.count - a.count);

function dataElementRows(source: DemoSaasSource): SaasRecordRow[] {
  const rows = new Map<string, SaasRecordRow>();
  for (const file of source.files) {
    for (const finding of file.findings) {
      const row = rows.get(finding.entityType) ?? { name: finding.entityType, categoryLabel: finding.categoryLabel, count: 0 };
      row.count += finding.count;
      rows.set(finding.entityType, row);
    }
  }
  return byCount([...rows.values()]);
}

/** A class counts its files, as the console's Document Classes do. */
function documentClassRows(source: DemoSaasSource): SaasRecordRow[] {
  const rows = new Map<string, SaasRecordRow>();
  for (const file of source.files) {
    const match = documentClassOf(source, file);
    if (!match) continue;
    const row = rows.get(match.documentClass) ?? { name: match.documentClass, categoryLabel: match.category, count: 0 };
    row.count += 1;
    rows.set(match.documentClass, row);
  }
  return byCount([...rows.values()]);
}

// ─── Files ───────────────────────────────────────────────────────────────────

const fileIdOf = (source: DemoSaasSource, index: number) =>
  `de5${String(source.number).padStart(3, '0')}${String(index).padStart(18, '0')}`;

/** The SaaS list's source id of a spec number (`xamlensMockData.ts`, `sourceIdOf`: `de3`, surface digit 1). */
const saasSourceId = (number: number) => `de31${String(number).padStart(20, '0')}`;

/** The file with this id, with its datastore and its index among the spec files; null for an unknown id. */
function fileOf(fileId: string): { source: DemoSaasSource; file: DemoSourceFile; index: number } | null {
  const match = fileId.match(/^de5(\d{3})(\d{18})$/);
  if (!match) return null;
  const source = demoSaasSource(saasSourceId(Number(match[1])));
  const index = Number(match[2]);
  const file = source?.files[index];
  return source && file ? { source, file, index } : null;
}

const intParam = (params: URLSearchParams, name: string, fallback: number, min: number, max: number) =>
  Math.min(max, Math.max(min, Number.parseInt(params.get(name) ?? '', 10) || fallback));

/**
 * `/saas/files`: exactly one of `dataElement` and `documentClass`, a row of its view, else the 400
 * sentinel; files holding it by records descending, searched by name, paged by `skip` and `limit` (5).
 */
function filePage(source: DemoSaasSource, params: URLSearchParams): SaasFilePage | DemoInvalidRequest {
  const dataElement = params.get('dataElement');
  const documentClass = params.get('documentClass');
  if ((dataElement === null) === (documentClass === null)) return DEMO_INVALID_REQUEST.dataElement;
  const kind = dataElement !== null ? 'dataElement' : 'documentClass';
  const value = (dataElement ?? documentClass ?? '').trim();
  const rows = kind === 'dataElement' ? dataElementRows(source) : documentClassRows(source);
  if (!rows.some(row => row.name === value)) return DEMO_INVALID_REQUEST[kind];

  const q = (params.get('q') ?? '').trim().toLowerCase();
  const files = source.files
    .map((file, index) => ({
      id: fileIdOf(source, index),
      name: file.name,
      records: kind === 'dataElement'
        ? file.findings.find(finding => finding.entityType === value)?.count ?? 0
        : documentClassOf(source, file)?.documentClass === value ? file.records : -1,
    }))
    .filter(file => (kind === 'dataElement' ? file.records > 0 : file.records >= 0))
    .filter(file => !q || file.name.toLowerCase().includes(q))
    .sort((a, b) => b.records - a.records);
  const skip = intParam(params, 'skip', 0, 0, 10_000);
  const limit = intParam(params, 'limit', 5, 1, 100);
  return { rows: files.slice(skip, skip + limit), skip, limit, total: files.length };
}

// ─── Information ─────────────────────────────────────────────────────────────

/** How a drive's supported files split by extension; the last one takes the rest. */
const TYPE_SHARES: Record<DemoSaasSource['drive'], ReadonlyArray<[extension: string, share: number]>> = {
  GMAIL: [['msg', 0.9], ['png', 0.07], ['pdf', 0]],
  GOOGLE_WORKSPACE: [['docx', 0.45], ['xlsx', 0.25], ['pdf', 0.2], ['csv', 0]],
};
/** `DatastoreLabels.saasFileTypeLabel`: msg Email, png Images, pdf PDF; others upper case. */
const FILE_TYPE_LABEL: Record<string, string> = { msg: 'Email', png: 'Images', pdf: 'PDF' };

function scanSummary(source: DemoSaasSource): SaasScanSummary {
  const number = (key: string) => Number(source.facet[key]) || 0;
  const supported = { count: number('supported_files_count'), sizeBytes: number('supported_files_size') };
  const unsupported = { count: number('unsupported_files_count'), sizeBytes: number('unsupported_files_size') };
  const shares = TYPE_SHARES[source.drive];
  let left = supported.count;
  const fileTypes: SaasScanSummaryFileType[] = shares.map(([extension, share], i) => {
    const count = i === shares.length - 1 ? left : Math.round(supported.count * share);
    left -= count;
    return { extension, label: FILE_TYPE_LABEL[extension] ?? extension.toUpperCase(), count };
  }).filter(type => type.count > 0)
    .sort((a, b) => b.count - a.count || (a.label ?? '').localeCompare(b.label ?? '') || a.extension.localeCompare(b.extension));
  return {
    supported,
    unsupported,
    fileTypes,
    properties: {
      owner: source.email,
      datastoreSizeBytes: number('total_size_bytes'),
      createdOn: daysAgo(120 + source.number),
      lastScan: source.lastScannedAt,
    },
  };
}

function header(source: DemoSaasSource): SaasDrawerHeader {
  // A scanning row's backfill is still running; a row never scanned has not started one.
  const backfill = source.status === 'CLASSIFIED' ? 'COMPLETED' : source.status === 'SCANNING' ? 'IN_PROGRESS' : 'NOT_STARTED';
  return {
    driveType: source.drive,
    driveTypeLabel: DRIVE_LABEL[source.drive],
    accountName: ACCOUNT_NAME,
    backfillState: backfill,
    backfillLabel: SCAN_STATE_LABEL[backfill],
    incrementalState: 'DISABLED',
    incrementalLabel: SCAN_STATE_LABEL.DISABLED,
  };
}

/**
 * `GET /api/dspm/datastores/{sourceId}/saas/{resource}` for a SaaS row: a DTO, the 400 sentinel for a
 * `files` value that is no row of its view, or null for an id that is not a SaaS row (the router's 404).
 */
export function demoSaasResource(id: string, resource: string, url: string): object | null {
  const source = demoSaasSource(id);
  if (!source) return null;
  const params = new URL(url, 'http://localhost').searchParams;
  switch (resource) {
    case 'detail': return header(source);
    case 'records': {
      const view = params.get('view') === 'document_classes' ? 'document_classes' : 'data_elements';
      const rows = view === 'data_elements' ? dataElementRows(source) : documentClassRows(source);
      return { view, rows } satisfies SaasRecords;
    }
    case 'files': return filePage(source, params);
    case 'scan-summary': return scanSummary(source);
    default: return null;
  }
}

// ─── The file drawer ─────────────────────────────────────────────────────────

const PRIYA = { name: 'Priya Nair', email: 'priya@xammer.in' };
const PRIYA_MAC = 'Priya’s MacBook Pro';
/** The HR colleague who receives the showcase mail (a spec person, so a `@xammer.in` address). */
const COLLEAGUE = 'meera@xammer.in';

type Showcase = 'gmailOffer' | 'driveOffer' | 'driveRoster';

/** The showcase files by spec id and name (design 7.2). */
function showcaseOf(source: DemoSaasSource, file: DemoSourceFile): Showcase | null {
  if (source.id === 'demo-saas-03' && file.name === 'Offer Letter - Priya Nair.pdf') return 'gmailOffer';
  if (source.id === 'demo-saas-04' && file.name === 'Offer Letter - Priya Nair.docx') return 'driveOffer';
  if (source.id === 'demo-saas-04' && file.name === 'Team Roster.xlsx') return 'driveRoster';
  return null;
}

const EMPTY_SHARING: SaasFileSharing = {
  internalEmails: [], externalEmails: [], internalGroups: [], externalGroups: [],
  privateShare: false, publicShare: false, orgWide: false, externalShare: false,
};

function sharingOf(source: DemoSaasSource, showcase: Showcase | null): SaasFileSharing {
  if (showcase === 'driveRoster') return { ...EMPTY_SHARING, privateShare: true };
  if (showcase === 'driveOffer') {
    return { ...EMPTY_SHARING, internalEmails: [PRIYA.email, COLLEAGUE], externalEmails: ['talent.partner@example.com'], externalShare: true };
  }
  const colleague = source.email === COLLEAGUE ? PRIYA.email : COLLEAGUE;
  return { ...EMPTY_SHARING, internalEmails: source.drive === 'GMAIL' ? [source.email, colleague] : [source.email] };
}

const metadataEntry = (key: string, value: string): SaasFileMetadataEntry =>
  ({ key, label: key.charAt(0).toUpperCase() + key.slice(1).toLowerCase(), value });

/** Text metadata as the backend keeps it: a message's ids and folder, an attachment's message, a Drive file's id. */
function metadataOf(source: DemoSaasSource, file: DemoSourceFile, index: number): SaasFileMetadataEntry[] {
  const ref = `${String(source.number).padStart(2, '0')}${String(index).padStart(2, '0')}`;
  if (source.drive !== 'GMAIL') {
    return [metadataEntry('id', `demo-drive-${ref}`), metadataEntry('name', file.name)];
  }
  if (file.extension === 'msg') {
    return [metadataEntry('id', `demo-msg-${ref}`), metadataEntry('threadId', `demo-thread-${ref}`), metadataEntry('mailFolderType', 'Inbox')];
  }
  return [metadataEntry('mailFolderType', 'Inbox'), metadataEntry('message_id', `demo-msg-${ref}`), metadataEntry('part_id', '1')];
}

/** When the file last changed: the showcase's own time, else a few days before the row's scan. */
function modifiedAtOf(source: DemoSaasSource, index: number, showcase: Showcase | null): number {
  if (showcase === 'gmailOffer') return daysAgo(40);
  if (showcase === 'driveOffer') return daysAgo(39);
  return (source.lastScannedAt ?? daysAgo(60)) - (index + 1) * 3 * DAY;
}

function overviewOf(source: DemoSaasSource, file: DemoSourceFile, index: number): SaasFileOverview {
  const showcase = showcaseOf(source, file);
  const documentClass = documentClassOf(source, file);
  const extension = file.extension;
  return {
    name: file.name,
    fileExtension: extension,
    fileTypeLabel: extension === 'msg' ? 'Outlook Message' : extension?.toUpperCase() ?? null,
    driveType: source.drive,
    accountName: ACCOUNT_NAME,
    owner: source.email,
    categoryLabel: documentClass?.category ?? null,
    documentClassLabel: documentClass?.documentClass ?? null,
    // Gmail reports every attachment and message as 0 bytes (GAP-54).
    sizeBytes: source.drive === 'GMAIL' ? 0 : 18_432 + index * 7_168,
    structured: extension === 'xlsx' || extension === 'csv',
    lastModifiedAt: modifiedAtOf(source, index, showcase),
    lastModifiedBy: source.email,
    sensitiveRecords: file.records,
    policyViolations: 0,
    sharing: sharingOf(source, showcase),
    sensitives: byCount(file.findings.map(finding => ({
      dataElement: finding.entityType, categoryLabel: finding.categoryLabel, count: finding.count,
    }))),
    metadata: metadataOf(source, file, index),
  };
}

function activityOf(showcase: Showcase | null): SaasFileActivity {
  if (showcase !== 'gmailOffer') return { entries: [] };
  return {
    entries: [
      { title: 'Sensitivity Label Applied', status: 'Completed', actor: PRIYA.email, occurredAt: daysAgo(12) },
      { title: 'Access Review Requested', status: 'Completed', actor: COLLEAGUE, occurredAt: daysAgo(30) },
    ],
  };
}

type LaneSeed = Omit<SaasFileLineageLane, 'key' | 'self'>;

const gmailLane = (fileName: string, firstSeenAt: number): LaneSeed => ({
  fileName, ownerName: PRIYA.name, ownerEmail: PRIYA.email, driveType: 'GMAIL', os: null, provider: 'GOOGLE_WORKSPACE',
  channelLabel: 'Gmail', firstSeenAt,
});
const driveLane = (fileName: string, firstSeenAt: number): LaneSeed => ({
  fileName, ownerName: PRIYA.name, ownerEmail: PRIYA.email, driveType: 'GOOGLE_WORKSPACE', os: null,
  provider: 'GOOGLE_WORKSPACE', channelLabel: 'Google Drive', firstSeenAt,
});
const macLane = (fileName: string, firstSeenAt: number): LaneSeed => ({
  fileName, ownerName: PRIYA_MAC, ownerEmail: null, driveType: null, os: 'MAC', provider: 'ENDPOINT_AGENT',
  channelLabel: 'Endpoint', firstSeenAt,
});

/** The copies of the offer letter's content, in API order, with the index of the opened file's own copy. */
const OFFER_COPIES = () => ({
  gmailPdf: gmailLane('Offer Letter - Priya Nair.pdf', daysAgo(40)),
  driveDocx: driveLane('Offer Letter - Priya Nair.docx', daysAgo(39)),
  drivePdf: driveLane('Offer Letter - Priya Nair.pdf', daysAgo(38)),
  driveDocxCopy: driveLane('Offer Letter - Priya Nair (1).docx', daysAgo(21)),
  macDocx: macLane('offer-letter.docx', daysAgo(36)),
});

function lanesOf(showcase: Showcase | null): { lanes: SaasFileLineageLane[]; events: SaasFileLineageEvent[] } {
  const copies = OFFER_COPIES();
  const lanes = (seeds: LaneSeed[], self: number) => seeds.map((seed, i) => ({ ...seed, key: `copy-${i}`, self: i === self }));
  if (showcase === 'gmailOffer') {
    return {
      lanes: lanes([copies.gmailPdf, copies.driveDocx, copies.drivePdf], 0),
      // The Gmail file's own timeline event, which the drawer draws on the Gmail lane.
      events: [{ laneKey: 'copy-0', typeLabel: 'Downloaded', actor: PRIYA.email, occurredAt: daysAgo(39.5) }],
    };
  }
  if (showcase === 'driveOffer') {
    return {
      lanes: lanes([copies.gmailPdf, copies.driveDocx, copies.driveDocxCopy, copies.macDocx], 1),
      events: [
        { laneKey: 'copy-3', typeLabel: 'File Created', actor: 'priya', occurredAt: daysAgo(35.9) },
        { laneKey: 'copy-3', typeLabel: 'File Modified', actor: 'priya', occurredAt: daysAgo(12) },
      ],
    };
  }
  return { lanes: [], events: [] };
}

function copyOf(key: string, lane: LaneSeed, path: string | null): SaasFileCopy {
  const saas = lane.driveType !== null;
  return {
    key,
    fileName: lane.fileName,
    extension: lane.fileName?.split('.').pop()?.toLowerCase() ?? null,
    path,
    driveType: lane.driveType,
    os: lane.os,
    provider: lane.provider,
    sourceLabel: lane.channelLabel,
    locationLabel: saas ? `SaaS · ${lane.channelLabel}` : 'Endpoint · macOS',
    ownerName: lane.ownerName,
    ownerEmail: lane.ownerEmail,
    userOrDevice: saas ? lane.ownerEmail : 'priya',
  };
}

/** `locations` is the vendor's `summary.locations`: every distinct place of the content, the opened file's own included. */
function copiesOf(source: DemoSaasSource, showcase: Showcase | null): SaasFileCopies {
  const copies = OFFER_COPIES();
  if (showcase === 'gmailOffer') {
    // Gmail and Priya's Drive.
    return { available: true, copies: [copyOf('copy-1', copies.driveDocx, null), copyOf('copy-2', copies.drivePdf, null)], locations: 2 };
  }
  if (showcase === 'driveOffer') {
    return {
      available: true,
      copies: [
        copyOf('copy-0', copies.gmailPdf, null),
        copyOf('copy-2', copies.driveDocxCopy, null),
        copyOf('copy-3', copies.macDocx, '/Users/priya/Documents/offer-letter.docx'),
      ],
      // Gmail, Priya's Drive and the MacBook.
      locations: 3,
    };
  }
  // A Drive file is hashed, so its own place counts; Gmail files are not.
  return { available: true, copies: [], locations: source.drive === 'GMAIL' ? 0 : 1 };
}

/**
 * The offer letter's copies as the Mac's `offer-letter.docx` sees them (the Endpoint file drawer's showcase):
 * the Gmail PDF and the two Drive DOCX, then its own Mac lane as `self`.
 */
export function demoMacOfferLetterCopies(): { lineage: SaasFileLineage; copies: SaasFileCopies } {
  const copies = OFFER_COPIES();
  const seeds = [copies.gmailPdf, copies.driveDocx, copies.driveDocxCopy, copies.macDocx];
  const lanes = seeds.map((seed, i) => ({ ...seed, key: `copy-${i}`, self: i === 3 }));
  return {
    lineage: { available: true, coverageNote: COVERAGE_NOTE, lanes, events: [] },
    copies: {
      available: true,
      copies: [copyOf('copy-0', copies.gmailPdf, null), copyOf('copy-1', copies.driveDocx, null), copyOf('copy-2', copies.driveDocxCopy, null)],
      // Gmail, Priya's Drive and the MacBook.
      locations: 3,
    },
  };
}

/** The coverage note of a file's lineage, for the Endpoint file drawer's demo. */
export const DEMO_COVERAGE_NOTE = COVERAGE_NOTE;

/**
 * `GET /api/dspm/saas-files/{fileId}/{resource}` for a file a SaaS row lists, or null for an unknown id
 * (the router's 404). Every file but the showcases has no copies and no activity.
 */
export function demoSaasFileResource(fileId: string, resource: string): object | null {
  const found = fileOf(fileId);
  if (!found) return null;
  const { source, file, index } = found;
  const showcase = showcaseOf(source, file);
  switch (resource) {
    case 'overview': return overviewOf(source, file, index);
    case 'activity': return activityOf(showcase);
    case 'lineage': return { available: true, coverageNote: COVERAGE_NOTE, ...lanesOf(showcase) } satisfies SaasFileLineage;
    case 'copies': return copiesOf(source, showcase);
    default: return null;
  }
}

/** The overview of a file a SaaS row lists, which the Files page's SaaS rows read too; null for an unknown id. */
export function demoSaasFileOverview(fileId: string): SaasFileOverview | null {
  const found = fileOf(fileId);
  return found ? overviewOf(found.source, found.file, found.index) : null;
}

/** The SaaS list's source id of the datastore holding this file; null for an unknown id. */
export function demoSaasFileSourceId(fileId: string): string | null {
  return fileOf(fileId)?.source.sourceId ?? null;
}

/** Every SaaS spec file's id: the SaaS datastores in spec order, each one's files in spec order. */
export function demoSaasFileIds(): string[] {
  const ids: string[] = [];
  for (let number = 1; ; number++) {
    const source = demoSaasSource(saasSourceId(number));
    if (!source) return ids;
    source.files.forEach((_, index) => ids.push(fileIdOf(source, index)));
  }
}

/** The SaaS document classes with their categories, in the order the subject keywords are tried. */
export function demoSaasDocumentClasses(): Array<{ category: string; documentClass: string }> {
  return DOCUMENT_CLASSES.map(([, category, documentClass]) => ({ category, documentClass }));
}
