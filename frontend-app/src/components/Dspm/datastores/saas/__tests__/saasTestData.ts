/*
 * Synthetic SaaS drawer data for the tests: the shapes of the backend's DTOs with invented names, never
 * values from the captured responses.
 */
import type {
  SaasFileCopies, SaasFileCopy, SaasFileLineage, SaasFileLineageEvent, SaasFileLineageLane, SaasFileOverview,
} from '../../../../../types/dspmDrawers';

/** Epoch seconds of a local time, so formatted expectations hold in every time zone. */
export const local = (year: number, month: number, day: number, hours = 0, minutes = 0) =>
  new Date(year, month - 1, day, hours, minutes).getTime() / 1000;

export const OWNER = 'pat@example.com';

/** A Gmail attachment's overview; `over` replaces fields. */
export function overview(over: Partial<SaasFileOverview> = {}): SaasFileOverview {
  return {
    name: 'travel-itinerary.pdf',
    fileExtension: 'pdf',
    fileTypeLabel: 'PDF',
    driveType: 'GMAIL',
    accountName: 'example-workspace',
    owner: OWNER,
    categoryLabel: 'Legal',
    documentClassLabel: 'General Contracts & Agreements',
    sizeBytes: 0,
    structured: true,
    lastModifiedAt: local(2026, 9, 10, 12, 54),
    lastModifiedBy: OWNER,
    sensitiveRecords: 10,
    policyViolations: 0,
    sharing: {
      internalEmails: ['sam@example.com', 'lee@example.com'],
      externalEmails: [],
      internalGroups: [],
      externalGroups: [],
      privateShare: false,
      publicShare: false,
      orgWide: false,
      externalShare: false,
    },
    sensitives: [
      { dataElement: 'PERSON NAME', categoryLabel: 'Personal', count: 6 },
      { dataElement: 'EMAIL ADDRESS', categoryLabel: 'Personal', count: 4 },
    ],
    metadata: [
      { key: 'id', label: 'Id', value: 'msg-0001' },
      { key: 'threadId', label: 'Threadid', value: 'thread-0001' },
      { key: 'mailFolderType', label: 'Mailfoldertype', value: 'Sent' },
    ],
    ...over,
  };
}

/** A Google Drive file's overview. */
export const driveOverview = (over: Partial<SaasFileOverview> = {}): SaasFileOverview => overview({
  name: 'Offer Letter - Pat Example',
  fileExtension: 'docx',
  fileTypeLabel: 'DOCX',
  driveType: 'GOOGLE_WORKSPACE',
  sharing: {
    internalEmails: [], externalEmails: [], internalGroups: [], externalGroups: [],
    privateShare: true, publicShare: false, orgWide: false, externalShare: false,
  },
  metadata: [{ key: 'mimeType', label: 'Mimetype', value: 'application/vnd.example.document' }],
  ...over,
});

/** A lineage lane shaped like a copy of the Drive DOCX answer. */
export function lane(key: string, over: Partial<SaasFileLineageLane> = {}): SaasFileLineageLane {
  return {
    key,
    fileName: `${key}.pdf`,
    ownerName: 'Pat Example',
    ownerEmail: OWNER,
    driveType: 'GOOGLE_WORKSPACE',
    os: null,
    provider: 'GOOGLE_WORKSPACE',
    channelLabel: 'Google Drive',
    self: false,
    firstSeenAt: null,
    ...over,
  };
}

export const event = (over: Partial<SaasFileLineageEvent> = {}): SaasFileLineageEvent => ({
  laneKey: null, typeLabel: 'Shared', actor: 'sam@example.com', occurredAt: null, ...over,
});

export const lineage = (over: Partial<SaasFileLineage> = {}): SaasFileLineage => ({
  available: true, coverageNote: null, lanes: [], events: [], ...over,
});

/** A copy shaped like the File Copies answer. */
export function copy(key: string, over: Partial<SaasFileCopy> = {}): SaasFileCopy {
  return {
    key,
    fileName: `${key}.pdf`,
    extension: 'pdf',
    path: null,
    driveType: 'GOOGLE_WORKSPACE',
    os: null,
    provider: 'GOOGLE_WORKSPACE',
    sourceLabel: 'Google Drive',
    locationLabel: 'SaaS · Google Drive',
    ownerName: 'Pat Example',
    ownerEmail: OWNER,
    userOrDevice: OWNER,
    ...over,
  };
}

export const copies = (list: SaasFileCopy[], locations: number, available = true): SaasFileCopies =>
  ({ available, copies: list, locations });
