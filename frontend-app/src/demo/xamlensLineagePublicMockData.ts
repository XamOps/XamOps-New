/**
 * The Data Lineage public path's demo answers (`/api/dspm/lineage/public/…`, build 7 design 7). The demo
 * account sees this path by default, as the switch-off backend answers it. Everything is invented: no
 * tenant, person, device or file name from a real project, e-mails at example.com.
 *
 * Datastores: 4 endpoints with files (one with 9, one whose count is unknown), plus one endpoint with 0
 * files that is left out as the backend leaves it out; then 5 Google Drive people. Endpoint files carry an
 * actor, a modified and a creation date; drive files only a last-seen date.
 */
import type { SaasFileLineage, SaasFileLineageEvent, SaasFileLineageLane } from '../types/dspmDrawers';
import type { EndpointFileLineageAsset } from '../types/dspmFiles';
import type {
  PublicFileLineage, PublicLineageDatastore, PublicLineageDatastorePage, PublicLineageFile, PublicLineageFilePage,
} from '../types/dspmLineage';
import type { DemoLineageInvalid } from './xamlensLineageMockData';

// ─── Data ──────────────────────────────────────────────────────────────────────────────────────

const at = (month: number, day: number, hour: number, minute: number) => Date.UTC(2026, month - 1, day, hour, minute) / 1000;
const pad = (value: number, width: number) => String(value).padStart(width, '0');

interface DemoPublicFile extends PublicLineageFile {
  id: string;
  name: string;
  /** Drive: the file answers `asset: null`. */
  noAsset?: boolean;
}

interface DemoPublicDatastore extends PublicLineageDatastore {
  id: string;
  name: string;
  /** The OS user (endpoint) or the owner e-mail (drive). */
  owner: string;
  fileList: DemoPublicFile[];
}

interface EndpointSeed {
  name: string;
  owner: string;
  files: string[];
  /** False: the count call failed, so the node shows no chip. */
  counted: boolean;
}

const ENDPOINT_SEEDS: EndpointSeed[] = [
  {
    name: 'Design Studio MacBook Pro', owner: 'harper', counted: true,
    files: [
      'brand-guidelines.pdf', 'campaign-budget.xlsx', 'logo-drafts.sketch', 'client-briefs.docx', 'photo-releases.pdf',
      'storyboard-v2.pptx', 'agency-invoices.csv', 'font-licences.txt', 'press-kit.zip',
    ],
  },
  { name: 'Finance ThinkPad X1', owner: 'quinn', counted: true, files: ['ledger-2026.xlsx', 'vendor-iban-list.csv', 'audit-notes.docx'] },
  { name: 'Reception iMac', owner: 'emery', counted: false, files: ['visitor-log.csv', 'badge-photos.zip'] },
  {
    name: 'Build Agent Mac mini', owner: 'ci-runner', counted: true,
    files: ['signing-config.env', 'release-manifest.json', 'crash-dumps.log', 'beta-testers.csv'],
  },
  { name: 'Spare Loaner Laptop', owner: 'loaner', counted: true, files: [] },
];

interface DriveSeed {
  name: string;
  email: string;
  /** The drive-lineage resource id: letters and digits in mixed case. */
  resource: string;
  files: string[];
}

const DRIVE_INTEGRATION = '7a1b2c3d4e5f60718293a4b5';

const DRIVE_SEEDS: DriveSeed[] = [
  {
    name: 'Morgan Hale', email: 'morgan.hale@example.com', resource: 'mHaLe01QxZ',
    files: ['Hiring Plan 2026', 'offer-template.docx', 'salary-bands.xlsx', 'interview-scorecards.pdf', 'org-chart.png', 'Team Offsite Notes'],
  },
  {
    name: 'Rowan Ellis', email: 'rowan.ellis@example.com', resource: 'rElLiS02Wv',
    files: ['Quarterly Board Deck', 'cap-table.xlsx', 'investor-updates.pdf', 'Due Diligence Checklist'],
  },
  {
    name: 'Tess Navarro', email: 'tess.navarro@example.com', resource: 'tNaVaRr03Yu',
    files: ['customer-renewals.xlsx', 'Support Escalations', 'contract-redlines.docx'],
  },
  { name: 'Kai Brennan', email: 'kai.brennan@example.com', resource: 'kBrEnN04Ts', files: ['Sprint Retro', 'api-keys-rotation.txt'] },
  {
    name: 'Ines Laurent', email: 'ines.laurent@example.com', resource: 'iLaUrE05Rq',
    files: ['travel-policy.pdf', 'Expense Claims Q3', 'card-statements.csv', 'reimbursements.xlsx', 'Policy FAQ'],
  },
];

const extensionOf = (name: string): string | null => {
  const match = /\.([A-Za-z0-9]{1,8})$/.exec(name);
  return match ? match[1].toLowerCase() : null;
};

let fileNumber = 0;

const ENDPOINTS: DemoPublicDatastore[] = ENDPOINT_SEEDS.map((seed, e) => ({
  id: `ep-6d0e1a2b3c4d5e6f7a8b${pad(e + 1, 4)}`,
  kind: 'ENDPOINT',
  name: seed.name,
  service: 'ENDPOINT',
  files: seed.counted ? seed.files.length : null,
  owner: seed.owner,
  fileList: seed.files.map((name, f): DemoPublicFile => {
    fileNumber += 1;
    const dateAt = at(1 + ((fileNumber * 5) % 10), 1 + ((fileNumber * 7) % 27), 8 + (fileNumber % 9), (fileNumber * 13) % 60);
    return {
      id: `e9d10000-0000-4000-8000-${pad(fileNumber, 12)}`,
      name,
      extension: extensionOf(name),
      actor: seed.owner,
      dateAt,
      // Reception iMac's second file has no creation time: its band shows "First Seen".
      createdAt: e === 2 && f === 1 ? null : dateAt - (12 + f) * 86_400,
    };
  }),
}));

const DRIVES: DemoPublicDatastore[] = DRIVE_SEEDS.map((seed, d) => ({
  id: `dr-${DRIVE_INTEGRATION}-${seed.resource}`,
  kind: 'DRIVE',
  name: seed.name,
  service: 'GOOGLE_DRIVE',
  files: seed.files.length,
  owner: seed.email,
  fileList: seed.files.map((name, f): DemoPublicFile => {
    fileNumber += 1;
    return {
      // Drive doc ids as Google returns them: 33 characters, mixed case.
      id: `1DmO${pad(d + 1, 2)}${pad(f + 1, 2)}xQvLtRwZpYkNbHcJfGsAeUiOy`,
      name,
      extension: extensionOf(name),
      actor: null,
      dateAt: at(2 + ((fileNumber * 3) % 9), 1 + ((fileNumber * 11) % 27), 9 + (fileNumber % 8), (fileNumber * 17) % 60),
      createdAt: null,
      // Kai Brennan's second file has no lineage asset yet.
      noAsset: d === 3 && f === 1,
    };
  }),
}));

/** The backend leaves out an endpoint with 0 files. */
const DATASTORES = [...ENDPOINTS, ...DRIVES].filter(datastore => datastore.fileList.length > 0);

// ─── Request parsing, as the backend does it ───────────────────────────────────────────────────

const ROW_LIMIT = 50;
const FILE_LIMIT = 100;
const DEFAULT_LIMIT = 7;
const MAX_SKIP = 10_000;
const DIGITS = /^\d{1,5}$/;

const invalid = (parameter: string, message: string): DemoLineageInvalid => ({ invalidParameter: parameter, message });

function pageOf(params: URLSearchParams, max: number): { skip: number; limit: number } | DemoLineageInvalid {
  const rawSkip = params.get('skip');
  const rawLimit = params.get('limit');
  if (rawSkip !== null && (!DIGITS.test(rawSkip) || Number(rawSkip) > MAX_SKIP)) {
    return invalid('skip', 'skip must be an integer from 0 to 10000');
  }
  if (rawLimit !== null && (!DIGITS.test(rawLimit) || Number(rawLimit) < 1 || Number(rawLimit) > max)) {
    return invalid('limit', `limit must be an integer from 1 to ${max}`);
  }
  return { skip: rawSkip === null ? 0 : Number(rawSkip), limit: rawLimit === null ? DEFAULT_LIMIT : Number(rawLimit) };
}

const paramsOf = (url: string) => new URL(url, 'http://localhost').searchParams;
const isInvalid = (value: object): value is DemoLineageInvalid => 'invalidParameter' in value;
const datastoreOf = (id: string) => DATASTORES.find(datastore => datastore.id === id);

// ─── Handlers ──────────────────────────────────────────────────────────────────────────────────

const datastoreRow = ({ id, kind, name, service, files }: DemoPublicDatastore): PublicLineageDatastore =>
  ({ id, kind, name, service, files });

/** `GET /api/dspm/lineage/public/datastores?q&skip&limit`: name search (case-insensitive), then the page. */
export function demoPublicDatastores(url: string): PublicLineageDatastorePage | DemoLineageInvalid {
  const params = paramsOf(url);
  const page = pageOf(params, ROW_LIMIT);
  if (isInvalid(page)) return page;
  const q = (params.get('q') ?? '').trim().toLowerCase();
  const matching = q === '' ? DATASTORES : DATASTORES.filter(datastore => datastore.name.toLowerCase().includes(q));
  return {
    rows: matching.slice(page.skip, page.skip + page.limit).map(datastoreRow),
    total: matching.length,
    ...page,
  };
}

const fileRow = ({ id, name, extension, actor, dateAt, createdAt }: DemoPublicFile): PublicLineageFile =>
  ({ id, name, extension, actor, dateAt, createdAt });

/** `GET /api/dspm/lineage/public/datastores/{id}/files?skip&limit`: one page; null for an unknown id. */
export function demoPublicFiles(id: string, url: string): PublicLineageFilePage | DemoLineageInvalid | null {
  const page = pageOf(paramsOf(url), FILE_LIMIT);
  if (isInvalid(page)) return page;
  const datastore = datastoreOf(id);
  if (!datastore) return null;
  return {
    rows: datastore.fileList.slice(page.skip, page.skip + page.limit).map(fileRow),
    total: datastore.fileList.length,
    ...page,
  };
}

const UNAVAILABLE: SaasFileLineage = { available: false, coverageNote: null, lanes: [], events: [] };

function copyLane(key: string, fileName: string, owner: string, self: boolean, firstSeenAt: number): SaasFileLineageLane {
  return {
    key, fileName, ownerName: owner, ownerEmail: null, driveType: null, os: 'MAC', provider: 'ENDPOINT_AGENT',
    channelLabel: 'Endpoint', self, firstSeenAt,
  };
}

function endpointLineage(datastore: DemoPublicDatastore, file: DemoPublicFile): PublicFileLineage {
  const modified = file.dateAt ?? 0;
  const firstSeen = (file.createdAt ?? modified - 20 * 86_400) + 600;
  const others = ENDPOINTS.filter(other => other !== datastore && other.fileList.length > 0);
  const other = others[(fileNumberOf(file)) % others.length];
  const events: SaasFileLineageEvent[] = [
    { laneKey: null, typeLabel: 'File Modified', actor: datastore.owner, occurredAt: modified },
    { laneKey: null, typeLabel: 'File Copied', actor: datastore.owner, occurredAt: modified + 3_600 },
  ];
  const asset: EndpointFileLineageAsset = {
    title: file.name, device: datastore.name, osUser: datastore.owner, firstSeenAt: firstSeen, events,
  };
  const copyName = file.name.replace(/(\.[^.]+)?$/, ' (1)$1');
  const copies: SaasFileLineage = {
    available: true,
    coverageNote: null,
    lanes: [
      copyLane('copy-0', file.name, datastore.name, true, firstSeen),
      copyLane('copy-1', copyName, other.name, false, modified + 3_900),
    ],
    events: [],
  };
  return { kind: 'ENDPOINT', asset, copies, ownerEmail: null };
}

function driveLineage(datastore: DemoPublicDatastore, file: DemoPublicFile): PublicFileLineage {
  if (file.noAsset) return { kind: 'DRIVE', asset: null, copies: UNAVAILABLE, ownerEmail: null };
  const lastSeen = file.dateAt ?? 0;
  const firstSeen = lastSeen - 30 * 86_400;
  const viewer = DRIVES[(DRIVES.indexOf(datastore) + 1) % DRIVES.length].owner;
  const events: SaasFileLineageEvent[] = [
    { laneKey: null, typeLabel: 'Shared', actor: datastore.owner, occurredAt: firstSeen + 2 * 86_400 },
  ];
  if (fileNumberOf(file) % 2 === 0) {
    events.push({ laneKey: null, typeLabel: 'Viewed', actor: viewer, occurredAt: lastSeen - 3_600 });
  }
  const asset: EndpointFileLineageAsset = { title: file.name, device: null, osUser: null, firstSeenAt: firstSeen, events };
  return { kind: 'DRIVE', asset, copies: UNAVAILABLE, ownerEmail: datastore.owner };
}

const fileNumberOf = (file: DemoPublicFile) => Number(file.id.replace(/\D/g, '').slice(-4));

/** `GET /api/dspm/lineage/public/datastores/{id}/files/{fileId}/lineage`; null for an unknown id. */
export function demoPublicFileLineage(id: string, fileId: string): PublicFileLineage | null {
  const datastore = datastoreOf(id);
  if (!datastore) return null;
  const wanted = datastore.kind === 'ENDPOINT' ? fileId.toLowerCase() : fileId;
  const file = datastore.fileList.find(candidate => candidate.id === wanted);
  if (!file) return null;
  return datastore.kind === 'ENDPOINT' ? endpointLineage(datastore, file) : driveLineage(datastore, file);
}
