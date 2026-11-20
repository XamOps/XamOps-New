/**
 * The Data Lineage page's demo answers (`/api/dspm/lineage/…`, build 4 design 7). By default the demo
 * account sees the page as the switch-off backend answers it (greyed category cards); with
 * `sessionStorage.xamlens_demo_lineage_steps = 'on'` it sees a switched-on backend with invented data that
 * reaches every step. All names are invented: no tenant, person, device or file name from a real project.
 */
import type { DatastoreFilterOptions } from '../types/dspmDatastores';
import type { SaasFileLineage, SaasFileLineageEvent, SaasFileLineageLane } from '../types/dspmDrawers';
import type {
  LineageCategories, LineageDatastore, LineageDatastorePage, LineageFile, LineageFilePage, LineagePolicy,
  LineagePolicyPage,
} from '../types/dspmLineage';

/** Flip to true to make the demo account show the switched-on page by default (design 4.7). */
export const DEMO_LINEAGE_STEPS_DEFAULT = false;
/** sessionStorage key read beside demo_mode; 'on' or 'off' overrides the default. */
export const DEMO_LINEAGE_STEPS_KEY = 'xamlens_demo_lineage_steps';

export function demoLineageStepsOn(): boolean {
  const stored = sessionStorage.getItem(DEMO_LINEAGE_STEPS_KEY);
  if (stored === 'on') return true;
  if (stored === 'off') return false;
  return DEMO_LINEAGE_STEPS_DEFAULT;
}

/** A parameter the backend refuses with 400 INVALID_REQUEST, and its message. */
export interface DemoLineageInvalid {
  readonly invalidParameter: string;
  readonly message: string;
}

export const isDemoLineageInvalid = (body: unknown): body is DemoLineageInvalid =>
  typeof body === 'object' && body !== null && 'invalidParameter' in body;

const invalid = (parameter: string, message: string): DemoLineageInvalid => ({ invalidParameter: parameter, message });
const notAnOption = (parameter: string) => invalid(parameter, `${parameter} is not an option of this project`);

// ─── Data ──────────────────────────────────────────────────────────────────────────────────────

const CATEGORIES = [
  ['SENSITIVE_FINDINGS', 'Sensitive Findings'],
  ['EXCESSIVE_EXPOSURE', 'Excessive Exposure'],
  ['HIGH_VOLUME_ACCESS', 'High Volume Access'],
  ['STALE_DATA', 'Stale Data'],
  ['RISKY_USER', 'Risky User'],
  ['PUBLIC_EXPOSURE', 'Public Exposure'],
] as const;
type CategoryId = typeof CATEGORIES[number][0];

type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM';
const SEVERITIES: ReadonlyArray<[Severity, string]> = [['CRITICAL', 'Critical'], ['HIGH', 'High'], ['MEDIUM', 'Medium']];
const SEVERITY_LABEL: Record<Severity, string> = { CRITICAL: 'Critical', HIGH: 'High', MEDIUM: 'Medium' };
const LABELS = ['PII', 'PCI', 'PHI', 'SECRETS'] as const;
type Label = typeof LABELS[number];

type Service = 'ENDPOINT' | 'ONE_DRIVE' | 'SHARE_POINT';

interface DemoSource {
  name: string;
  service: Service;
  /** The lineage band's actor for this datastore's files. */
  actor: string;
}

const SOURCES = {
  studioMac: { name: 'Studio MacBook Pro', service: 'ENDPOINT', actor: 'alex' },
  opsMac: { name: 'Ops MacBook Air', service: 'ENDPOINT', actor: 'sam' },
  supportMini: { name: 'Support Mac mini', service: 'ENDPOINT', actor: 'robin' },
  financeDrive: { name: 'Finance OneDrive', service: 'ONE_DRIVE', actor: 'finance.lead@example.com' },
  peopleDrive: { name: 'People Ops OneDrive', service: 'ONE_DRIVE', actor: 'people.ops@example.com' },
  legalSite: { name: 'Legal SharePoint', service: 'SHARE_POINT', actor: 'legal.team@example.com' },
} satisfies Record<string, DemoSource>;
type SourceKey = keyof typeof SOURCES;

interface DemoExposureSeed {
  source: SourceKey;
  severity: Severity;
  files: number;
}

interface DemoPolicySeed {
  category: CategoryId;
  name: string;
  severity: Severity;
  service: Service;
  labels: Label[];
  exposures: DemoExposureSeed[];
}

const POLICY_SEEDS: DemoPolicySeed[] = [
  {
    category: 'SENSITIVE_FINDINGS', name: 'Endpoints holding files with 10+ personal records', severity: 'MEDIUM',
    service: 'ENDPOINT', labels: ['PII'],
    exposures: [
      { source: 'studioMac', severity: 'HIGH', files: 9 },
      { source: 'opsMac', severity: 'MEDIUM', files: 3 },
      { source: 'supportMini', severity: 'MEDIUM', files: 2 },
    ],
  },
  {
    category: 'SENSITIVE_FINDINGS', name: 'Shared drives with payment card numbers', severity: 'HIGH',
    service: 'SHARE_POINT', labels: ['PCI'],
    exposures: [{ source: 'legalSite', severity: 'HIGH', files: 4 }],
  },
  {
    category: 'SENSITIVE_FINDINGS', name: 'OneDrive files with health records', severity: 'CRITICAL',
    service: 'ONE_DRIVE', labels: ['PHI', 'PII'],
    exposures: [
      { source: 'peopleDrive', severity: 'CRITICAL', files: 3 },
      { source: 'financeDrive', severity: 'HIGH', files: 2 },
    ],
  },
  {
    category: 'SENSITIVE_FINDINGS', name: 'Endpoints storing plain-text secrets', severity: 'CRITICAL',
    service: 'ENDPOINT', labels: ['SECRETS'],
    exposures: [
      { source: 'studioMac', severity: 'CRITICAL', files: 2 },
      { source: 'supportMini', severity: 'HIGH', files: 1 },
    ],
  },
  {
    category: 'SENSITIVE_FINDINGS', name: 'OneDrive spreadsheets with bank account numbers', severity: 'HIGH',
    service: 'ONE_DRIVE', labels: ['PCI', 'PII'],
    exposures: [{ source: 'financeDrive', severity: 'HIGH', files: 3 }],
  },
  {
    category: 'SENSITIVE_FINDINGS', name: 'SharePoint libraries with scanned identity documents', severity: 'HIGH',
    service: 'SHARE_POINT', labels: ['PII'],
    exposures: [{ source: 'legalSite', severity: 'MEDIUM', files: 2 }],
  },
  {
    category: 'SENSITIVE_FINDINGS', name: 'Laptops with exported customer lists', severity: 'MEDIUM',
    service: 'ENDPOINT', labels: ['PII'],
    exposures: [{ source: 'opsMac', severity: 'MEDIUM', files: 2 }],
  },
  {
    category: 'SENSITIVE_FINDINGS', name: 'Drives with tax identifiers', severity: 'MEDIUM',
    service: 'ONE_DRIVE', labels: ['PII'],
    exposures: [{ source: 'peopleDrive', severity: 'MEDIUM', files: 2 }],
  },
  {
    category: 'SENSITIVE_FINDINGS', name: 'Endpoints with API tokens in config files', severity: 'HIGH',
    service: 'ENDPOINT', labels: ['SECRETS'],
    exposures: [
      { source: 'studioMac', severity: 'HIGH', files: 1 },
      { source: 'opsMac', severity: 'CRITICAL', files: 1 },
    ],
  },
  {
    category: 'EXCESSIVE_EXPOSURE', name: 'SharePoint sites shared with everyone in the organisation', severity: 'HIGH',
    service: 'SHARE_POINT', labels: ['PII'],
    exposures: [{ source: 'legalSite', severity: 'HIGH', files: 2 }],
  },
  {
    category: 'STALE_DATA', name: 'OneDrive files untouched for two years', severity: 'MEDIUM',
    service: 'ONE_DRIVE', labels: ['PII'],
    exposures: [{ source: 'financeDrive', severity: 'MEDIUM', files: 1 }],
  },
  {
    category: 'STALE_DATA', name: 'Endpoint archives older than 18 months', severity: 'MEDIUM',
    service: 'ENDPOINT', labels: ['PHI'],
    exposures: [{ source: 'supportMini', severity: 'MEDIUM', files: 1 }],
  },
];

/** Invented file names; the first exposure lists the same name twice (copies are separate nodes). */
const FILE_NAMES = [
  'q3-forecast.xlsx', 'onboarding-checklist.docx', 'vendor-contracts.pdf', 'payroll-march.csv',
  'customer-export.csv', 'q3-forecast.xlsx', 'benefits-summary.pdf', 'card-settlements.xlsx',
  'intake-forms.pdf', 'release-notes.md', 'travel-claims.xlsx', 'board-update.pptx', 'tax-forms-2025.pdf',
  'support-tickets.csv', 'config-backup.env', 'deploy-keys.txt', 'clinic-referrals.docx', 'bank-details.xlsx',
];

const at = (month: number, day: number, hour: number, minute: number) => Date.UTC(2026, month - 1, day, hour, minute) / 1000;

interface DemoFile extends LineageFile {
  id: string;
  name: string;
  modifiedAt: number;
  source: DemoSource;
  /** False: the file answers `available: false`. */
  lineage: boolean;
}

interface DemoExposure extends LineageDatastore {
  id: string;
  name: string;
  severity: Severity;
  fileList: DemoFile[];
}

interface DemoPolicy extends LineagePolicy {
  id: string;
  name: string;
  severity: Severity;
  category: CategoryId;
  labels: Label[];
  exposureList: DemoExposure[];
}

const pad = (value: number, width: number) => String(value).padStart(width, '0');

function buildPolicies(): DemoPolicy[] {
  let fileNumber = 0;
  return POLICY_SEEDS.map((seed, p) => {
    const policyId = `5e1f0c00-0000-4000-8000-${pad(p + 1, 12)}`;
    const exposureList = seed.exposures.map((exposure, e): DemoExposure => {
      const source: DemoSource = SOURCES[exposure.source];
      const fileList = Array.from({ length: exposure.files }, (_, f): DemoFile => {
        fileNumber += 1;
        const name = FILE_NAMES[(p * 3 + e * 5 + f) % FILE_NAMES.length];
        return {
          // Endpoint files carry UUIDs, drive files 24 hex.
          id: source.service === 'ENDPOINT'
            ? `f11e0000-0000-4000-8000-${pad(fileNumber, 12)}`
            : `d71e${pad(fileNumber, 20)}`,
          name,
          extension: name.split('.').pop() ?? null,
          actor: source.actor,
          modifiedAt: at(1 + ((fileNumber * 5) % 11), 1 + ((fileNumber * 7) % 27), 8 + (fileNumber % 9), (fileNumber * 13) % 60),
          source,
          lineage: true,
        };
      });
      return {
        id: `exp-${pad(p + 1, 2)}-${exposure.source.toLowerCase()}`,
        name: source.name,
        service: source.service,
        files: fileList.length,
        severity: exposure.severity,
        fileList,
      };
    });
    return {
      id: policyId,
      name: seed.name,
      severity: seed.severity,
      severityLabel: SEVERITY_LABEL[seed.severity],
      service: seed.service,
      datastores: exposureList.length,
      category: seed.category,
      labels: seed.labels,
      exposureList,
    };
  });
}

const POLICIES = buildPolicies();
// The last file of the first policy's second datastore has no lineage recorded yet.
POLICIES[0].exposureList[1].fileList[2].lineage = false;

const EXPOSURES = POLICIES.flatMap(policy => policy.exposureList);
const FILES = EXPOSURES.flatMap(exposure => exposure.fileList);
const DATASTORE_NAMES = [...new Set(Object.values(SOURCES).map(source => source.name))].sort((a, b) => a.localeCompare(b));

// ─── Request parsing, as the backend does it ───────────────────────────────────────────────────

const ROW_LIMIT = 50;
const FILE_LIMIT = 100;
const DEFAULT_LIMIT = 7;

function page(params: URLSearchParams, max: number): { skip: number; limit: number } {
  const skip = Number(params.get('skip') ?? 0);
  const limit = Number(params.get('limit') ?? DEFAULT_LIMIT);
  return {
    skip: Number.isInteger(skip) && skip >= 0 ? skip : 0,
    limit: Number.isInteger(limit) && limit >= 1 ? Math.min(limit, max) : DEFAULT_LIMIT,
  };
}

const paramsOf = (url: string) => new URL(url, 'http://localhost').searchParams;

const isCategory = (value: string | null): value is CategoryId => CATEGORIES.some(([id]) => id === value);

/**
 * The values a dimension keeps after its Include or Exclude: Include keeps the chosen options, Exclude the
 * complement in option order. Undefined: no filter on the dimension. A value outside `options` is refused.
 */
function kept(params: URLSearchParams, dimension: string, options: readonly string[]): string[] | undefined | DemoLineageInvalid {
  for (const exclude of [false, true]) {
    const parameter = exclude ? `${dimension}_exclude` : dimension;
    const values = params.getAll(parameter).flatMap(raw => raw.split(',')).map(value => value.trim()).filter(Boolean);
    if (values.length === 0) continue;
    const unknown = values.find(value => !options.includes(value));
    if (unknown !== undefined) return notAnOption(parameter);
    return options.filter(option => values.includes(option) !== exclude);
  }
  return undefined;
}

const search = (params: URLSearchParams) => (params.get('q') ?? '').trim().toLowerCase();

const SEVERITY_VALUES = SEVERITIES.map(([value]) => value);

// ─── Handlers ──────────────────────────────────────────────────────────────────────────────────

/** `GET /api/dspm/lineage/categories`. */
export function demoLineageCategories(): LineageCategories {
  const on = demoLineageStepsOn();
  return {
    available: on,
    categories: CATEGORIES.map(([id, name]) => ({
      id, name, exposures: on ? POLICIES.filter(policy => policy.category === id).length : null,
    })),
  };
}

/** `GET /api/dspm/lineage/filter-options?level=policy|datastore`. */
export function demoLineageFilterOptions(url: string): DatastoreFilterOptions | DemoLineageInvalid {
  const level = paramsOf(url).get('level');
  if (level !== 'policy' && level !== 'datastore') return invalid('level', 'level must be policy or datastore');
  if (!demoLineageStepsOn()) return { dimensions: [] };
  const dimension = (key: string, options: ReadonlyArray<[string, string]>) =>
    ({ key, options: options.map(([value, label]) => ({ value, label, count: null })), hasMore: false });
  const severity = dimension('severity', SEVERITIES);
  if (level === 'datastore') return { dimensions: [severity] };
  return {
    dimensions: [
      severity,
      dimension('datastoreName', DATASTORE_NAMES.map(name => [name, name])),
      dimension('label', LABELS.map(label => [label, label])),
    ],
  };
}

const policyRow = ({ id, name, severity, severityLabel, service, datastores }: DemoPolicy): LineagePolicy =>
  ({ id, name, severity, severityLabel, service, datastores });

/** `GET /api/dspm/lineage/policies?category=…`: search, the three filters, then the page. */
export function demoLineagePolicies(url: string): LineagePolicyPage | DemoLineageInvalid {
  const params = paramsOf(url);
  const category = params.get('category');
  if (!isCategory(category)) return invalid('category', 'category must be one of the exposure categories');
  const { skip, limit } = page(params, ROW_LIMIT);
  if (!demoLineageStepsOn()) return { available: false, rows: [], total: 0, skip, limit };
  const severities = kept(params, 'severity', SEVERITY_VALUES);
  const names = kept(params, 'datastoreName', DATASTORE_NAMES);
  const labels = kept(params, 'label', LABELS);
  for (const selection of [severities, names, labels]) if (isDemoLineageInvalid(selection)) return selection;
  const [s, n, l] = [severities, names, labels] as Array<string[] | undefined>;
  // An Exclude of every option leaves nothing to match: no rows, as the backend answers without a call.
  if ([s, n, l].some(values => values?.length === 0)) return { available: true, rows: [], total: 0, skip, limit };
  const q = search(params);
  const matching = POLICIES.filter(policy => policy.category === category
    && (q === '' || policy.name.toLowerCase().includes(q))
    && (!s || s.includes(policy.severity))
    && (!n || policy.exposureList.some(exposure => n.includes(exposure.name)))
    && (!l || policy.labels.some(label => l.includes(label))));
  return { available: true, rows: matching.slice(skip, skip + limit).map(policyRow), total: matching.length, skip, limit };
}

const datastoreRow = ({ id, name, service, files }: DemoExposure): LineageDatastore => ({ id, name, service, files });

/** `GET /api/dspm/lineage/policies/{policyId}/datastores`: search, Severity, then the page. Null: unknown policy. */
export function demoLineageDatastores(policyId: string, url: string): LineageDatastorePage | DemoLineageInvalid | null {
  const params = paramsOf(url);
  const { skip, limit } = page(params, ROW_LIMIT);
  if (!demoLineageStepsOn()) return { available: false, rows: [], total: 0, skip, limit };
  const policy = POLICIES.find(candidate => candidate.id === policyId.toLowerCase());
  if (!policy) return null;
  const severities = kept(params, 'severity', SEVERITY_VALUES);
  if (isDemoLineageInvalid(severities)) return severities;
  if (severities?.length === 0) return { available: true, rows: [], total: 0, skip, limit };
  const q = search(params);
  const matching = policy.exposureList.filter(exposure => (q === '' || exposure.name.toLowerCase().includes(q))
    && (!severities || severities.includes(exposure.severity)));
  return { available: true, rows: matching.slice(skip, skip + limit).map(datastoreRow), total: matching.length, skip, limit };
}

const fileRow = ({ id, name, extension, actor, modifiedAt }: DemoFile): LineageFile => ({ id, name, extension, actor, modifiedAt });

/** `GET /api/dspm/lineage/datastores/{exposureId}/files`: one page; `total` counts every file. Null: unknown id. */
export function demoLineageFiles(exposureId: string, url: string): LineageFilePage | null {
  const { skip, limit } = page(paramsOf(url), FILE_LIMIT);
  if (!demoLineageStepsOn()) return { available: false, rows: [], total: 0, skip, limit };
  const exposure = EXPOSURES.find(candidate => candidate.id === exposureId);
  if (!exposure) return null;
  return {
    available: true,
    rows: exposure.fileList.slice(skip, skip + limit).map(fileRow),
    total: exposure.fileList.length,
    skip,
    limit,
  };
}

const UNAVAILABLE_LINEAGE: SaasFileLineage = { available: false, coverageNote: null, lanes: [], events: [] };

const CHANNEL: Record<Service, string> = { ENDPOINT: 'Endpoint', ONE_DRIVE: 'OneDrive', SHARE_POINT: 'SharePoint' };

function laneOf(key: string, fileName: string, source: DemoSource, self: boolean, firstSeenAt: number): SaasFileLineageLane {
  const endpoint = source.service === 'ENDPOINT';
  return {
    key,
    fileName,
    ownerName: source.name,
    ownerEmail: endpoint ? null : source.actor,
    driveType: null,
    os: endpoint ? 'MAC' : null,
    provider: endpoint ? 'ENDPOINT_AGENT' : source.service,
    channelLabel: CHANNEL[source.service],
    self,
    firstSeenAt,
  };
}

/** `GET /api/dspm/lineage/files/{fileId}/lineage`: the file's own lane and one other copy. Null: unknown id. */
export function demoLineageFileLineage(fileId: string): SaasFileLineage | null {
  if (!demoLineageStepsOn()) return UNAVAILABLE_LINEAGE;
  const file = FILES.find(candidate => candidate.id === fileId.toLowerCase());
  if (!file) return null;
  if (!file.lineage) return UNAVAILABLE_LINEAGE;
  const created = file.modifiedAt - 9 * 86_400;
  const other = file.source === SOURCES.opsMac ? SOURCES.studioMac : SOURCES.opsMac;
  const copyName = file.name.replace(/(\.[^.]+)?$/, ' (1)$1');
  const lanes = [
    laneOf('copy-0', file.name, file.source, true, created),
    laneOf('copy-1', copyName, other, false, file.modifiedAt + 3_600),
  ];
  const events: SaasFileLineageEvent[] = [
    { laneKey: 'copy-0', typeLabel: 'File Created', actor: file.source.actor, occurredAt: created },
    { laneKey: 'copy-0', typeLabel: 'File Modified', actor: file.source.actor, occurredAt: file.modifiedAt },
    { laneKey: 'copy-1', typeLabel: 'File Copied', actor: other.actor, occurredAt: file.modifiedAt + 3_600 },
  ];
  return { available: true, coverageNote: null, lanes, events };
}
