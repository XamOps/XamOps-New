/**
 * XamLens page demo data (ROLE_XAMOPS), design 7: the Dashboard, Document Classes, Assists and
 * Remediation.
 *
 * The Dashboard is computed from the demo datastores that `xamlensMockData.ts` serves, with the backend's
 * rules (`DashboardMapper`), so every Top Risky row is a demo datastore whose drawer works and every bar's
 * link lands on rows the demo lists. The other pages' values are invented; none is copied from a capture.
 */
import type { DatastoreRow } from '../types/dspmDatastores';
import type {
  Assist, AssistList, DashboardEnvironment, DashboardEnvironmentId, DashboardRegion, DashboardSubCategory,
  DocumentClassList, DspmDashboard, RemediationActivity, RemediationItem, TopRiskyTab,
} from '../types/dspmPages';
import { demoDatastoreKpis, demoDatastores } from './xamlensMockData';

const ENVIRONMENTS: DashboardEnvironmentId[] = ['cloud', 'saas', 'endpoint', 'on_prem'];
const TOP_RISKY_TABS: TopRiskyTab[] = ['all', ...ENVIRONMENTS];

/** As `DashboardMapper`: 5 Top Risky rows, at most 20 bars, the console's endpoint bar names. */
const TOP_ROWS = 5;
const MAX_SUB_CATEGORIES = 20;
const OS_BAR_LABELS: Record<string, string> = { MAC: 'Mac', WINDOWS: 'Windows' };

/** A tab's rows in Risk order, as the backend's collector pages them (one page holds the whole demo). */
const tabRows = (tab: TopRiskyTab): DatastoreRow[] =>
  demoDatastores(`/api/dspm/datastores?tab=${tab}&sort=-severity&page=1&pageSize=100`).data;

const records = (row: DatastoreRow) => row.sensitives.recordCount;

/** Bars by provider (cloud, on-prem), drive type (SaaS) or OS (endpoint), most records first. */
function subCategories(id: DashboardEnvironmentId, rows: DatastoreRow[]): DashboardSubCategory[] {
  const bySubtype = id === 'endpoint' || id === 'saas';
  const bars = new Map<string, DashboardSubCategory>();
  for (const row of rows) {
    const key = bySubtype ? row.subtype : row.source.provider;
    if (key === null) continue;
    let bar = bars.get(key);
    if (!bar) {
      const label = id === 'endpoint' ? (OS_BAR_LABELS[key] ?? row.subtypeLabel)
        : id === 'saas' ? row.subtypeLabel
          : row.source.providerLabel;
      bar = { key, label: label ?? key, sensitiveRecords: 0, datastores: 0 };
      bars.set(key, bar);
    }
    bar.sensitiveRecords += records(row);
    bar.datastores += 1;
  }
  return [...bars.values()]
    .sort((a, b) => b.sensitiveRecords - a.sensitiveRecords
      || a.label.localeCompare(b.label, 'en', { sensitivity: 'base' }))
    .slice(0, MAX_SUB_CATEGORIES);
}

function environment(id: DashboardEnvironmentId): DashboardEnvironment {
  const rows = tabRows(id);
  const kpis = demoDatastoreKpis(`/api/dspm/datastores/kpis?tab=${id}`);
  return {
    id,
    datastores: rows.length,
    sensitiveRecords: rows.reduce((sum, row) => sum + records(row), 0),
    recordsTrend: kpis.totalSensitiveRecords.trend,
    connected: rows.length > 0,
    filter: id === 'endpoint' ? 'os' : id === 'saas' ? null : 'provider',
    subCategories: subCategories(id, rows),
    partial: false,
  };
}

/** As `DashboardMapper.cloudRank`: AWS, GCP (or a GCP multi-region), Azure, anything else. */
function cloudRank(code: string): number {
  if (/^[a-z]{2}(-[a-z]+)+-\d+$/.test(code)) return 0;
  if (/^[a-z]+(-[a-z]+)+\d+$/.test(code) || ['us', 'eu', 'asia', 'europe'].includes(code)) return 1;
  return /^[a-z]+\d*$/.test(code) ? 2 : 3;
}

/**
 * Active Sensitive Regions, as `DashboardMapper.regions`: the demo cloud rows per `source.region`, EBS
 * volumes left out; AWS, then GCP, then Azure regions, each cloud's by its oldest `sourceId`, then the code.
 */
function regions(): DashboardRegion[] {
  const byRegion = new Map<string, { region: DashboardRegion; oldest: string | null; labels: Map<string, number> }>();
  for (const row of tabRows('cloud')) {
    const code = row.source.region;
    if (!code || code.trim() === '' || row.source.provider === 'EBS') continue;
    let entry = byRegion.get(code);
    if (!entry) {
      entry = { region: { region: code, datastores: 0, scannedDatastores: 0, sensitiveLabels: [] }, oldest: null, labels: new Map() };
      byRegion.set(code, entry);
    }
    entry.region.datastores += 1;
    if (row.scan.status === 'CLASSIFIED') entry.region.scannedDatastores += 1;
    if (/^[0-9a-f]{24}$/.test(row.sourceId ?? '') && (entry.oldest === null || row.sourceId! < entry.oldest)) {
      entry.oldest = row.sourceId;
    }
    for (const label of row.sensitives.typeLabels) entry.labels.set(label, (entry.labels.get(label) ?? 0) + 1);
  }
  const order = (a: string | null, b: string | null) => (a === b ? 0 : a === null ? 1 : b === null ? -1 : a < b ? -1 : 1);
  return [...byRegion.values()]
    .sort((a, b) => cloudRank(a.region.region) - cloudRank(b.region.region) || order(a.oldest, b.oldest)
      || order(a.region.region, b.region.region))
    .map(entry => ({
      ...entry.region,
      sensitiveLabels: [...entry.labels.entries()].sort((a, b) => b[1] - a[1]).map(([label]) => label),
    }));
}

/** `GET /api/dspm/dashboard`. */
export function demoDashboard(): DspmDashboard {
  const topRisky = Object.fromEntries(TOP_RISKY_TABS.map(tab =>
    [tab, tabRows(tab).filter(row => records(row) > 0).slice(0, TOP_ROWS)])) as Record<TopRiskyTab, DatastoreRow[]>;
  return { environments: ENVIRONMENTS.map(environment), topRisky, regions: regions() };
}

// ─── Document Classes ───────────────────────────────────────────────────────────────────────────

/** 11 invented categories of 3 classes each, in route order. */
const DEMO_CLASSES: ReadonlyArray<[string, string[]]> = [
  ['Commercial Agreements', ['Master Service Agreements', 'Non-Disclosure Agreements', 'Supplier Contracts']],
  ['Payroll Records', ['Salary Slips', 'Tax Withholding Forms', 'Bonus Letters']],
  ['Engineering Specs', ['Design Documents', 'Architecture Diagrams', 'Test Plans']],
  ['Customer Correspondence', ['Support Transcripts', 'Complaint Letters', 'Customer Invoices']],
  ['Board Materials', ['Board Minutes', 'Shareholder Letters', 'Strategy Decks']],
  ['Sourcing & Buying', ['Purchase Orders', 'Vendor Quotes', 'Goods Receipts']],
  ['Marketing Assets', ['Campaign Briefs', 'Press Releases', 'Brand Guidelines']],
  ['Legal Filings', ['Court Submissions', 'Trademark Filings', 'Regulatory Notices']],
  ['Facilities', ['Lease Agreements', 'Visitor Logs', 'Maintenance Reports']],
  ['Training Content', ['Course Handbooks', 'Certification Records', 'Onboarding Guides']],
  ['Travel & Expenses', ['Expense Claims', 'Travel Itineraries', 'Mileage Logs']],
];

/** `GET /api/dspm/document-classes`. */
export function demoDocumentClasses(): DocumentClassList {
  return {
    categories: DEMO_CLASSES.map(([category]) => category),
    rows: DEMO_CLASSES.flatMap(([category, names]) => names.map(name => ({ name, category }))),
  };
}

// ─── Assists ────────────────────────────────────────────────────────────────────────────────────

const DAY = 86_400;

/** `GET /api/dspm/assists`: an active one, a disabled one without a version, one expiring in 12 days, one expired. */
export function demoAssists(nowMs: number = Date.now()): AssistList {
  const now = Math.floor(nowMs / 1000);
  const assist = (over: Partial<Assist> & Pick<Assist, 'id' | 'name'>): Assist => ({
    clientId: null, healthStatus: 'ACTIVE', statusLabel: 'Active', assistType: 'ON_PREM',
    assistTypeLabel: 'On Prem', version: '2.4.1', secretExpiresAt: now + 140 * DAY, ...over,
  });
  return {
    assists: [
      assist({ id: '6d656d6f0000000000000001', name: 'warehouse-connector', clientId: 'ZGVtby1hc3Npc3QtMDEtZXhhbXBsZS1jbGllbnQ=' }),
      assist({
        id: '6d656d6f0000000000000002', name: 'branch-office-relay', clientId: 'ZGVtby1hc3Npc3QtMDItZXhhbXBsZS1jbGllbnQ=',
        healthStatus: 'INACTIVE', statusLabel: 'Disabled', version: null, secretExpiresAt: now + 210 * DAY,
      }),
      assist({
        id: '6d656d6f0000000000000003', name: 'lab-network-scanner', clientId: 'ZGVtby1hc3Npc3QtMDMtZXhhbXBsZS1jbGllbnQ=',
        assistType: 'DISCOVERY', assistTypeLabel: 'Discovery', version: '2.3.0', secretExpiresAt: now + 12 * DAY - 3600,
      }),
      assist({
        id: '6d656d6f0000000000000004', name: 'legacy-file-gateway', clientId: 'ZGVtby1hc3Npc3QtMDQtZXhhbXBsZS1jbGllbnQ=',
        version: '1.9.7', secretExpiresAt: now - 5 * DAY,
      }),
    ],
  };
}

// ─── Remediation ────────────────────────────────────────────────────────────────────────────────

const RUN_LABELS: Record<string, string> = {
  WAITING_APPROVAL: 'Awaiting Approval', IN_PROGRESS: 'In Progress', SCHEDULED: 'Scheduled', COMPLETED: 'Completed',
  NOOP: 'No Change', FAILED: 'Failed',
};

/** Six invented runs across statuses, newest first; times are offsets from now in seconds. */
function demoRuns(now: number): RemediationItem[] {
  const run = (over: Partial<RemediationItem> & Pick<RemediationItem, 'id' | 'status'>): RemediationItem => ({
    actionType: 'MAKE_PRIVATE', actionLabel: 'Make Private', objectName: null, objectPath: null,
    datastoreType: 'GOOGLE_DRIVE', datastoreLabel: 'Google Drive', sourceType: 'SAAS',
    initiatedBy: { name: 'Riya Demo', email: 'riya.demo@example.com' }, triggerType: 'MANUAL', triggerLabel: 'Manual',
    createdAt: now - 3600, statusLabel: RUN_LABELS[over.status ?? ''] ?? null, approvers: [], scheduledAt: null, ...over,
  });
  return [
    run({
      id: 'rem-demo-0006', status: 'WAITING_APPROVAL', actionType: 'DELETE_FILE', actionLabel: 'Delete File',
      objectName: 'q3-salary-review.xlsx', objectPath: 'https://drive.example.com/file/d/demo-salary-review',
      triggerType: 'POLICY', triggerLabel: 'Policy', createdAt: now - 20 * 60,
      approvers: ['admin@example.com', 'security.lead@example.com'],
    }),
    run({
      id: 'rem-demo-0005', status: 'IN_PROGRESS', actionType: 'REMOVE_EXTERNAL_ACCESS', actionLabel: 'Remove External Access',
      objectName: 'partner-pricing.pdf', objectPath: 'https://drive.example.com/file/d/demo-partner-pricing',
      createdAt: now - 45 * 60,
    }),
    run({
      id: 'rem-demo-0004', status: 'SCHEDULED', actionType: 'SET_LINK_EXPIRY', actionLabel: 'Set Link Expiry',
      objectName: 'offsite-agenda.docx', objectPath: 'https://drive.example.com/file/d/demo-offsite-agenda',
      triggerType: 'AI_AGENT', triggerLabel: 'AI Agent', createdAt: now - 3 * 3600, scheduledAt: now + 20 * 3600,
    }),
    run({
      id: 'rem-demo-0003', status: 'COMPLETED', objectName: 'customer-export.csv',
      objectPath: 's3://demo-analytics-bucket/exports/customer-export.csv', datastoreType: 'S3', datastoreLabel: 'S3',
      sourceType: 'CLOUD', initiatedBy: { name: null, email: 'ops.bot@example.com' }, createdAt: now - DAY,
    }),
    run({
      id: 'rem-demo-0002', status: 'FAILED', actionType: 'MOVE_TO_QUARANTINE', actionLabel: 'Move to Quarantine',
      objectName: 'Re: passport scans', objectPath: null, datastoreType: 'GMAIL', datastoreLabel: 'Gmail',
      triggerType: 'POLICY', triggerLabel: 'Policy', createdAt: now - 2 * DAY,
    }),
    run({
      id: 'rem-demo-0001', status: 'NOOP', actionType: 'DOWNGRADE_LINK_SCOPE', actionLabel: 'Downgrade Link Scope',
      objectName: 'team-roster.xlsx', objectPath: 'https://drive.example.com/file/d/demo-team-roster', createdAt: now - 4 * DAY,
    }),
  ];
}

/**
 * `GET /api/dspm/remediation/activity`: the runs matching `q` (object name or path) and `actionType`;
 * `status` narrows the rows and the total, while the cards and counts cover every status, as their
 * tooltips say; then `skip` and `limit`.
 */
export function demoRemediation(url: string, nowMs: number = Date.now()): RemediationActivity {
  const params = new URL(url, 'http://localhost').searchParams;
  const q = (params.get('q') ?? '').trim().toLowerCase();
  const status = params.get('status');
  const actionType = params.get('actionType');
  const skip = Math.max(0, Number(params.get('skip') ?? 0) || 0);
  const limit = Math.min(100, Math.max(1, Number(params.get('limit') ?? 10) || 10));
  const matching = demoRuns(Math.floor(nowMs / 1000)).filter(run =>
    (q === '' || `${run.objectName ?? ''} ${run.objectPath ?? ''}`.toLowerCase().includes(q))
    && (actionType === null || run.actionType === actionType));
  const count = (...statuses: string[]) => matching.filter(run => statuses.includes(run.status ?? '')).length;
  const statusCounts: Record<string, number> = { ALL: matching.length };
  for (const code of ['WAITING_APPROVAL', 'IN_PROGRESS', 'SCHEDULED', 'COMPLETED', 'FAILED', 'ROLLED_BACK']) {
    statusCounts[code] = count(code);
  }
  const rows = status === null ? matching : matching.filter(run => run.status === status);
  return {
    summary: {
      total: matching.length,
      waitingApproval: count('WAITING_APPROVAL'),
      inProgressScheduled: count('IN_PROGRESS', 'SCHEDULED'),
      completed: count('COMPLETED'),
      failed: count('FAILED'),
    },
    statusCounts,
    total: rows.length,
    skip,
    limit,
    items: rows.slice(skip, skip + limit),
  };
}
