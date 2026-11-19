/**
 * XamLens Scan Management demo data (ROLE_XAMOPS), design 7: schedules, scan history, a scope, the SaaS
 * integrations with their users and Slack channels, and the endpoint configuration. Every value is
 * invented; none is copied from a capture. Filters and paging are applied as the backend would, so the
 * filters, the empty states and the footers can all be shown.
 */
import type { DspmPage } from '../types/dspm';
import type {
  EndpointScanConfiguration, SaasChannel, SaasIntegrationList, SaasScanConfiguration, SaasScanUser, ScanRun,
  ScanRunDatastore, ScanRunDetail, ScanSchedule, ScanScheduleDetail, ScanScope, ScanScopeDetail, ScanTarget,
} from '../types/dspmScans';

const DAY = 86_400;
const BASE = '/api/dspm/scan-management';

// ─── ids ───────────────────────────────────────────────────────────────────────────────────────

const LEGACY_SCHEDULE = '6d656d6f0000000000000a01';
const WEEKLY_SCHEDULE = '3f8a2c10-5b7e-4d21-9c3a-0de0a0000001';
const SCOPE_ID = '6d656d6f0000000000000b01';
const GOOGLE_INTEGRATION = '6d656d6f0000000000000c01';
const SLACK_INTEGRATION = '6d656d6f0000000000000c02';
const runId = (n: number) => `9b1e4f20-7c3d-4a85-b6e2-1ac0d00000${String(n).padStart(2, '0')}`;

// ─── paging and filters ────────────────────────────────────────────────────────────────────────

function paged<T>(rows: T[], params: URLSearchParams): DspmPage<T> {
  const page = Math.max(1, Number(params.get('page') ?? 1) || 1);
  const pageSize = Math.min(100, Math.max(1, Number(params.get('pageSize') ?? 10) || 10));
  const start = (page - 1) * pageSize;
  return {
    data: rows.slice(start, start + pageSize),
    page,
    pageSize,
    total: rows.length,
    totalPages: Math.max(1, Math.ceil(rows.length / pageSize)),
  };
}

/** The rows whose `field` equals the parameter, or all of them when it is not set. */
const matches = (params: URLSearchParams, name: string, value: string | null | undefined) =>
  !params.has(name) || params.get(name) === value;

const searched = (params: URLSearchParams, ...texts: Array<string | null>) => {
  const q = (params.get('q') ?? '').trim().toLowerCase();
  return q === '' || texts.some(text => (text ?? '').toLowerCase().includes(q));
};

// ─── targets ───────────────────────────────────────────────────────────────────────────────────

const target = (n: number, name: string, subtype: string, subtypeLabel: string): ScanTarget => ({
  id: `6d656d6f00000000000d${String(n).padStart(4, '0')}`, name, subtype, subtypeLabel,
});

const CLOUD_TARGETS: ScanTarget[] = [
  target(1, 'demo-billing-archive', 'S3', 'S3'),
  target(2, 'demo-orders-replica', 'RDS', 'RDS'),
  target(3, 'demo-session-cache', 'DYNAMODB', 'DynamoDB'),
];

// ─── schedules ─────────────────────────────────────────────────────────────────────────────────

function schedules(now: number): ScanSchedule[] {
  return [
    {
      id: LEGACY_SCHEDULE, name: null, platform: 'cloud', platformLabel: 'Cloud',
      coverage: 'SELECTED_DATASTORES', coverageLabel: 'Selected datastores',
      totalDatastores: 12, totalIsEstimate: false, state: 'ACTIVE', legacy: true,
      timing: {
        type: 'once', triggerTime: now + 3 * DAY, recurrenceType: null, repeatOn: [], dayOfMonth: null,
        endDate: null, timezone: 'UTC',
      },
      lastRunAt: null, lastRunStatus: null, lastRunStatusLabel: null, nextRunAt: now + 3 * DAY,
    },
    {
      id: WEEKLY_SCHEDULE, name: 'Weekly laptop sweep', platform: 'endpoint', platformLabel: 'Endpoint',
      coverage: 'ALL_DATASTORES', coverageLabel: 'All datastores',
      totalDatastores: 6, totalIsEstimate: true, state: 'ACTIVE', legacy: false,
      timing: {
        type: 'recurring', triggerTime: now - 9 * DAY, recurrenceType: 'WEEKLY', repeatOn: ['MONDAY', 'THURSDAY'],
        dayOfMonth: null, endDate: null, timezone: 'UTC',
      },
      lastRunAt: now - 2 * DAY, lastRunStatus: 'SUCCESS', lastRunStatusLabel: 'Success', nextRunAt: now + 2 * DAY,
    },
  ];
}

function scheduleDetail(schedule: ScanSchedule): ScanScheduleDetail {
  if (schedule.id === LEGACY_SCHEDULE) {
    return { schedule, scopeMode: 'STATIC', scopeNames: ['Finance stores'], targets: CLOUD_TARGETS, targetsTotal: 12 };
  }
  return { schedule, scopeMode: 'ALL', scopeNames: [], targets: [], targetsTotal: 0 };
}

// ─── scan history ──────────────────────────────────────────────────────────────────────────────

interface RunSeed {
  n: number;
  name: string;
  platform: 'cloud' | 'on_prem' | 'endpoint';
  trigger: 'SCHEDULED' | 'IMMEDIATE';
  status: 'SUCCESS' | 'FAILED' | 'IN_PROGRESS';
  daysAgo: number;
  total: number;
}

const PLATFORM_LABELS = { cloud: 'Cloud', on_prem: 'On-Prem', endpoint: 'Endpoint' } as const;
const STATUS_LABELS = { SUCCESS: 'Success', FAILED: 'Failed', IN_PROGRESS: 'Progress' } as const;

const RUN_SEEDS: RunSeed[] = [
  { n: 1, name: 'Weekly laptop sweep', platform: 'endpoint', trigger: 'SCHEDULED', status: 'IN_PROGRESS', daysAgo: 0, total: 6 },
  { n: 2, name: 'Finance stores', platform: 'cloud', trigger: 'IMMEDIATE', status: 'FAILED', daysAgo: 1, total: 3 },
  { n: 3, name: 'Weekly laptop sweep', platform: 'endpoint', trigger: 'SCHEDULED', status: 'SUCCESS', daysAgo: 2, total: 6 },
  { n: 4, name: 'Ledger database check', platform: 'on_prem', trigger: 'IMMEDIATE', status: 'SUCCESS', daysAgo: 4, total: 1 },
  { n: 5, name: 'Weekly laptop sweep', platform: 'endpoint', trigger: 'SCHEDULED', status: 'SUCCESS', daysAgo: 5, total: 5 },
  { n: 6, name: 'Finance stores', platform: 'cloud', trigger: 'IMMEDIATE', status: 'SUCCESS', daysAgo: 8, total: 3 },
  { n: 7, name: 'Weekly laptop sweep', platform: 'endpoint', trigger: 'SCHEDULED', status: 'SUCCESS', daysAgo: 9, total: 5 },
  { n: 8, name: 'Weekly laptop sweep', platform: 'endpoint', trigger: 'SCHEDULED', status: 'SUCCESS', daysAgo: 12, total: 4 },
];

function runs(now: number): ScanRun[] {
  return RUN_SEEDS.map(seed => {
    const started = now - seed.daysAgo * DAY - 3600;
    const running = seed.status === 'IN_PROGRESS';
    const failed = seed.status === 'FAILED' ? 1 : 0;
    const completed = running ? 2 : seed.total;
    return {
      id: runId(seed.n), name: seed.name,
      coverage: seed.platform === 'endpoint' ? 'ALL_DATASTORES' : 'SELECTED_DATASTORES',
      coverageLabel: seed.platform === 'endpoint' ? 'All datastores' : 'Selected datastores',
      platform: seed.platform, platformLabel: PLATFORM_LABELS[seed.platform],
      trigger: seed.trigger, triggerLabel: seed.trigger === 'SCHEDULED' ? 'Scheduled' : 'Immediate',
      scheduledTime: started, startedAt: started, endedAt: running ? null : started + 1_260,
      durationSeconds: running ? null : 1_260,
      status: seed.status, statusLabel: STATUS_LABELS[seed.status],
      phase: running ? 'CLASSIFICATION' : 'COMPLETED', phaseLabel: running ? 'Classification' : 'Completed',
      statusCounts: { SUCCESS: completed - failed, FAILED: failed, IN_PROGRESS: running ? seed.total - completed : 0 },
      completedDatastores: completed, totalDatastores: seed.total,
      skippedCount: seed.n === 5 ? 1 : 0,
    };
  });
}

const DEVICE_NAMES = ['demo-mbp-design-01', 'demo-mbp-sales-02', 'demo-win-ops-03', 'demo-mbp-hr-04', 'demo-win-fin-05', 'demo-mbp-eng-06'];

function runDatastores(run: ScanRun): ScanRunDatastore[] {
  const status = (i: number): Pick<ScanRunDatastore, 'status' | 'statusLabel' | 'failureReason'> => {
    if (run.status === 'FAILED' && i === 0) {
      return { status: 'FAILED', statusLabel: 'Failed', failureReason: 'The assist could not reach the datastore before the scan timed out.' };
    }
    if (run.status === 'IN_PROGRESS' && i >= run.completedDatastores) {
      return { status: 'IN_PROGRESS', statusLabel: 'Progress', failureReason: null };
    }
    return { status: 'SUCCESS', statusLabel: 'Success', failureReason: null };
  };
  const count = run.totalDatastores;
  if (run.platform === 'endpoint') {
    return DEVICE_NAMES.slice(0, count).map((name, i) => ({
      id: `6d656d6f00000000000e${String(i).padStart(4, '0')}`, name,
      subtype: name.includes('-win-') ? 'WINDOWS' : 'MAC', subtypeLabel: name.includes('-win-') ? 'Windows' : 'macOS',
      accountName: null, region: null, ...status(i),
    }));
  }
  if (run.platform === 'on_prem') {
    return [{
      id: '6d656d6f00000000000f0001', name: 'demo_ledger', subtype: 'SELF_HOSTED_POSTGRES', subtypeLabel: 'PostgreSQL',
      accountName: 'demo-dc-1', region: 'self-hosted', ...status(0),
    }];
  }
  return CLOUD_TARGETS.slice(0, count).map((t, i) => ({
    id: t.id, name: t.name, subtype: t.subtype, subtypeLabel: t.subtypeLabel,
    accountName: i === 2 ? 'demo-staging' : 'demo-production', region: 'eu-west-1', ...status(i),
  }));
}

function runDetail(run: ScanRun): ScanRunDetail {
  const accounts = new Map<string, number>();
  for (const datastore of runDatastores(run)) {
    if (datastore.accountName) accounts.set(datastore.accountName, (accounts.get(datastore.accountName) ?? 0) + 1);
  }
  return {
    run,
    scopeNames: run.platform === 'cloud' ? ['Finance stores'] : [],
    skippedExamples: run.skippedCount > 0 ? ['demo-mbp-old-07'] : [],
    accounts: [...accounts].map(([value, count]) => ({ value, count })),
  };
}

// ─── scopes ────────────────────────────────────────────────────────────────────────────────────

function scopes(now: number): ScanScope[] {
  return [{
    id: SCOPE_ID, name: 'Finance stores', platform: 'cloud', platformLabel: 'Cloud', scopeMode: 'STATIC',
    totalDatastores: CLOUD_TARGETS.length, updatedAt: now - 6 * DAY,
  }];
}

// ─── SaaS ──────────────────────────────────────────────────────────────────────────────────────

const INTEGRATIONS: SaasIntegrationList = {
  integrations: [
    { id: GOOGLE_INTEGRATION, provider: 'GOOGLE_WORKSPACE', accountLabel: 'demo-workspace.example', driveTypes: ['GMAIL', 'GOOGLE_WORKSPACE'] },
    { id: SLACK_INTEGRATION, provider: 'SLACK', accountLabel: 'demo-team', driveTypes: ['SLACK'] },
  ],
  partial: false,
};

function saasConfiguration(id: string, now: number): SaasScanConfiguration | null {
  if (id === GOOGLE_INTEGRATION) {
    return {
      integrationId: id, realtimeEnabled: false, backfillingEnabled: true, samplingPercentage: 100,
      provisioningComplete: true, lastSavedAt: now - 5 * DAY, lastSavedBy: 'it.admin@example.com',
      scopes: [
        { driveType: 'GOOGLE_WORKSPACE', driveLabel: 'Google Drive', scopeType: 'SELECTED_USERS', totalActiveUsers: 23, supportedDomains: ['example.com', 'example.org'] },
        { driveType: 'GMAIL', driveLabel: 'Gmail', scopeType: 'ALL_USERS', totalActiveUsers: 23, supportedDomains: ['example.com', 'example.org'] },
      ],
    };
  }
  if (id === SLACK_INTEGRATION) {
    return {
      integrationId: id, realtimeEnabled: true, backfillingEnabled: false, samplingPercentage: null,
      provisioningComplete: false, lastSavedAt: null, lastSavedBy: null,
      scopes: [{ driveType: 'SLACK', driveLabel: 'Slack', scopeType: 'SELECTED_USERS', totalActiveUsers: 9, supportedDomains: [] }],
    };
  }
  return null;
}

const FIRST_NAMES = ['ava', 'ben', 'cara', 'dev', 'eli', 'fay', 'gus', 'hana', 'ivo', 'jun', 'kai', 'lena',
  'milo', 'nia', 'omar', 'pia', 'quin', 'rosa', 'sami', 'tess', 'uma', 'vik', 'wren'];

const USERS: SaasScanUser[] = FIRST_NAMES.map((name, i) => ({
  id: `6d656d6f0000000000010${String(i).padStart(3, '0')}`,
  email: `${name}.demo@${i % 4 === 3 ? 'example.org' : 'example.com'}`,
  status: i % 7 === 6 ? 'SUSPENDED' : 'ACTIVE',
  statusLabel: i % 7 === 6 ? 'Suspended' : 'Active',
  selected: i % 3 !== 2,
}));

const CHANNELS: SaasChannel[] = [
  { id: 'C0DEMO0001', name: 'demo-announcements', channelType: 'PUBLIC', channelStatus: 'ACTIVE', memberCount: 48, externalShared: false, topic: 'Company-wide announcements', isNew: false, selected: true },
  { id: 'C0DEMO0002', name: 'payroll-review', channelType: 'PRIVATE', channelStatus: 'ACTIVE', memberCount: 6, externalShared: true, topic: null, isNew: true, selected: true },
  { id: 'C0DEMO0003', name: 'old-launch-war-room', channelType: 'PUBLIC', channelStatus: 'ARCHIVED', memberCount: 17, externalShared: false, topic: 'Archived after the spring launch', isNew: false, selected: false },
];

function saasUsers(params: URLSearchParams): DspmPage<SaasScanUser> {
  const domain = params.get('domain');
  return paged(USERS.filter(user => searched(params, user.email)
    && matches(params, 'status', user.status)
    && (domain === null || (user.email ?? '').endsWith(`@${domain}`))), params);
}

function saasChannels(params: URLSearchParams): DspmPage<SaasChannel> {
  return paged(CHANNELS.filter(channel => searched(params, channel.name, channel.topic)
    && matches(params, 'channelType', channel.channelType)
    && matches(params, 'channelStatus', channel.channelStatus)
    && matches(params, 'scanState', channel.selected ? 'ENABLED' : 'DISABLED')
    && (params.get('isNew') !== 'true' || channel.isNew)), params);
}

// ─── endpoint ──────────────────────────────────────────────────────────────────────────────────

const ALL_FILE_TYPES = ['pdf', 'docx', 'doc', 'xlsx', 'xls', 'csv', 'pptx', 'txt', 'json', 'xml', 'zip', 'png', 'jpg', 'eml'];

const ENDPOINT_CONFIGURATION: EndpointScanConfiguration = {
  configured: true,
  scanMode: 'cloud',
  reconciliationStart: '01:00',
  reconciliationEnd: '05:00',
  includePaths: ['/Users', 'C:\\Users'],
  excludePaths: ['/Users/Shared/Caches'],
  fileTypes: ['pdf', 'docx', 'xlsx', 'csv', 'pptx', 'txt'],
  allFileTypes: ALL_FILE_TYPES,
};

// ─── router ────────────────────────────────────────────────────────────────────────────────────

const SCHEDULE = /^\/schedules\/([^/]+)$/;
const RUN = /^\/scan-history\/([^/]+)$/;
const RUN_DATASTORES = /^\/scan-history\/([^/]+)\/datastores$/;
const SCOPE = /^\/scan-scopes\/([^/]+)$/;
const SAAS = /^\/saas-integrations\/([^/]+)\/(configuration|users|channels)$/;

/**
 * Every `GET /api/dspm/scan-management/…` answer, or null for an unknown path or id (the router turns
 * that into the backend's 404 `ASSET_NOT_FOUND`).
 */
export function demoScanManagement(path: string, url: string, nowMs: number = Date.now()): unknown {
  if (!path.startsWith(`${BASE}/`)) return null;
  const rest = path.slice(BASE.length);
  const params = new URL(url, 'http://localhost').searchParams;
  const now = Math.floor(nowMs / 1000);

  if (rest === '/schedules') {
    return paged(schedules(now).filter(s => searched(params, s.name)
      && matches(params, 'platform', s.platform)
      && matches(params, 'status', s.lastRunStatus)
      && matches(params, 'coverage', s.coverage)), params);
  }
  if (rest === '/scan-history') {
    return paged(runs(now).filter(r => searched(params, r.name)
      && matches(params, 'coverage', r.coverage)
      && matches(params, 'platform', r.platform)
      && matches(params, 'status', r.status)
      && matches(params, 'trigger', r.trigger)), params);
  }
  if (rest === '/scan-scopes') {
    return paged(scopes(now).filter(s => searched(params, s.name) && matches(params, 'platform', s.platform)), params);
  }
  if (rest === '/saas-integrations') return INTEGRATIONS;
  if (rest === '/endpoint-configuration') return ENDPOINT_CONFIGURATION;

  const id = (match: RegExpMatchArray | null) => (match ? decodeURIComponent(match[1]) : null);
  const scheduleId = id(rest.match(SCHEDULE));
  if (scheduleId !== null) {
    const schedule = schedules(now).find(s => s.id === scheduleId);
    return schedule ? scheduleDetail(schedule) : null;
  }
  const runDatastoresId = id(rest.match(RUN_DATASTORES));
  if (runDatastoresId !== null) {
    const run = runs(now).find(r => r.id === runDatastoresId);
    if (!run) return null;
    return paged(runDatastores(run).filter(d => searched(params, d.name)
      && matches(params, 'account', d.accountName)
      && matches(params, 'status', d.status)), params);
  }
  const runDetailId = id(rest.match(RUN));
  if (runDetailId !== null) {
    const run = runs(now).find(r => r.id === runDetailId);
    return run ? runDetail(run) : null;
  }
  const scopeId = id(rest.match(SCOPE));
  if (scopeId !== null) {
    const scope = scopes(now).find(s => s.id === scopeId);
    return scope ? ({ scope, targets: CLOUD_TARGETS, targetsTotal: CLOUD_TARGETS.length } satisfies ScanScopeDetail) : null;
  }
  const saas = rest.match(SAAS);
  if (saas) {
    const integrationId = decodeURIComponent(saas[1]);
    const configuration = saasConfiguration(integrationId, now);
    if (configuration === null) return null;
    if (saas[2] === 'configuration') return configuration;
    if (saas[2] === 'users') {
      const driveType = params.get('driveType');
      if (!configuration.scopes.some(scope => scope.driveType === driveType) || driveType === 'SLACK') return paged([], params);
      return saasUsers(params);
    }
    return integrationId === SLACK_INTEGRATION ? saasChannels(params) : paged([], params);
  }
  return null;
}
