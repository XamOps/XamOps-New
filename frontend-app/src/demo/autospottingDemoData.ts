/**
 * Demo fixtures for the AutoSpotting shell, AWS (plan-phase-14 task 14.4) and GCP (plan-phase-26 task 26.5),
 * design-ux-ops.md section 5, AC-UI-06: one answer for every v2 path the shell calls. Shapes mirror
 * `api/autospottingApi.ts`. The GCP project answers for {@link DEMO_GCP_ACCOUNT_DB_ID}.
 *
 * Nothing here reaches the backend. Demo stays a session flag (`demoConfig.ts`); there is no URL
 * trigger. State that a user action changes (the group a confirm enables, the saved RI/SP setting) lives
 * in module variables and resets with {@link resetAutoSpottingDemo}.
 */
import type {
  Analytics,
  AnalyticsFigures,
  AuditEntry,
  AutoSpottingConfig,
  CommitmentSettings,
  GroupDetail,
  GroupList,
  GroupRow,
  Operation,
  OperatorOperations,
  OperatorSwitch,
  Plan,
  Potential,
} from '../api/autospottingApi';
import { demoAutoSpottingRispCoverage } from './xamopsMockData';

const NOW = () => new Date();
const iso = (offsetMs: number) => new Date(NOW().getTime() + offsetMs).toISOString();
const HOUR = 3_600_000;
const DAY = 24 * HOUR;

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------
/** Conversions stay paused in the demo, as in prod: Enable is shown disabled with the paused reason. */
export const demoAutoSpottingConfig: AutoSpottingConfig = {
  shellEnabled: true,
  dryRun: false,
  providers: {
    AWS: { enabled: true, conversionsEnabled: false, allowlistOnly: true, v2MutationsEnabled: false, beta: true },
    GCP: { enabled: true, conversionsEnabled: false, allowlistOnly: true, v2MutationsEnabled: false, beta: true },
  },
  engagedSwitches: [],
};

// ---------------------------------------------------------------------------
// Groups
// ---------------------------------------------------------------------------
type Seed = Pick<GroupRow, 'id' | 'location' | 'name' | 'state' | 'desired' | 'min' | 'max' | 'instanceType'> & {
  savings: number | null;
  blocker?: { code: string; text: string };
  /** GCP seeds carry their project: the group key is `project/location/name`. */
  project?: string;
};

export type DemoProvider = 'AWS' | 'GCP';

/** The demo GCP project's XamOps account id (`demo-gcp-001`). */
export const DEMO_GCP_ACCOUNT_DB_ID = 9101;
const GCP_PROJECT = 'gcp-analytics-demo';

const SEEDS: Seed[] = [
  { id: null, location: 'us-east-1', name: 'web-asg', state: 'UNMANAGED', desired: 6, min: 2, max: 12, instanceType: 'm5.large', savings: 820 },
  { id: 'demo-grp-api', location: 'us-east-1', name: 'api-asg', state: 'ACTIVE', desired: 8, min: 4, max: 16, instanceType: 'c5.xlarge', savings: 1240 },
  { id: null, location: 'us-east-1', name: 'batch-asg', state: 'UNMANAGED', desired: 10, min: 0, max: 40, instanceType: 'r5.large', savings: 960 },
  { id: null, location: 'eu-west-1', name: 'worker-asg', state: 'UNMANAGED', desired: 3, min: 1, max: 6, instanceType: 'm5.xlarge', savings: null },
  {
    id: null, location: 'ap-south-1', name: 'edge-asg', state: 'UNMANAGED', desired: 2, min: 2, max: 4, instanceType: 't3.medium', savings: 410,
    blocker: { code: 'EXTERNAL_SPOT', text: 'This group already runs Spot capacity that XamOps does not manage.' },
  },
];

const GCP_SEEDS: Seed[] = [
  { id: null, project: GCP_PROJECT, location: 'us-central1', name: 'web-mig', state: 'UNMANAGED', desired: 6, min: 3, max: 12, instanceType: 'n2-standard-4', savings: 640 },
  { id: 'demo-grp-gcp-api', project: GCP_PROJECT, location: 'us-central1', name: 'api-mig', state: 'ACTIVE', desired: 4, min: 4, max: 4, instanceType: 'c2-standard-8', savings: 910 },
  { id: null, project: GCP_PROJECT, location: 'us-central1-a', name: 'batch-mig', state: 'UNMANAGED', desired: 3, min: 3, max: 3, instanceType: 'e2-standard-2', savings: null },
  {
    id: null, project: GCP_PROJECT, location: 'us-central1-b', name: 'gke-analytics-pool-1-grp', state: 'UNMANAGED', desired: 3, min: 1, max: 5, instanceType: 'e2-standard-4', savings: 300,
    blocker: { code: 'GCP_GKE_NODE_POOL', text: "This instance group belongs to a GKE node pool, which GKE manages. It can't be converted here." },
  },
  {
    id: null, project: GCP_PROJECT, location: 'europe-west1', name: 'frontend-mig', state: 'UNMANAGED', desired: 4, min: 2, max: 8, instanceType: 'n2-standard-2', savings: 220,
    blocker: {
      code: 'GCP_NO_READINESS_GATE',
      text: 'New VMs could receive traffic before they are ready, so capacity behind the load balancer could dip during the rollout. Add an autohealing health check to this MIG, then plan again.',
    },
  },
];

const seedsOf = (provider: DemoProvider): Seed[] => (provider === 'GCP' ? GCP_SEEDS : SEEDS);

/** The cloud a demo account id answers for. */
export const demoProviderOf = (accountId: string | number | null | undefined): DemoProvider =>
  String(accountId) === String(DEMO_GCP_ACCOUNT_DB_ID) ? 'GCP' : 'AWS';

/** Per-group state a demo confirm or revert changes. */
const stateOverride = new Map<string, string>();

const keyOf = (s: Seed) => (s.project ? `${s.project}/${s.location}/${s.name}` : `${s.location}/${s.name}`);

function rowFrom(s: Seed): GroupRow {
  const state = stateOverride.get(keyOf(s)) ?? s.state;
  const blocked = !!s.blocker;
  const unpriced = s.savings === null;
  return {
    id: s.id ?? (stateOverride.has(keyOf(s)) ? `demo-grp-${s.name}` : null),
    provider: s.project ? 'GCP' : 'AWS',
    groupKey: keyOf(s),
    location: s.location,
    name: s.name,
    state,
    managed: state !== 'UNMANAGED',
    externalSpot: s.blocker?.code === 'EXTERNAL_SPOT',
    desired: s.desired,
    min: s.min,
    max: s.max,
    instanceType: s.instanceType,
    enableAllowed: !blocked,
    enableBlockedBy: s.blocker?.code ?? null,
    blockers: s.blocker ? [{ id: 'c-ext', code: s.blocker.code, text: s.blocker.text }] : [],
    warnings: [],
    deferred: [],
    monthlySavings: unpriced ? null : s.savings,
    savingsUnknown: unpriced,
    savingsLabel: unpriced ? 'Not priced' : 'Estimate',
  };
}

export function demoGroupList(provider: DemoProvider = 'AWS'): GroupList {
  const items = seedsOf(provider).map(rowFrom);
  const blockedCounts: Record<string, number> = {};
  items.forEach(r => r.blockers.forEach(b => (blockedCounts[b.code] = (blockedCounts[b.code] ?? 0) + 1)));
  const priced = items.filter(r => !r.savingsUnknown && r.monthlySavings !== null);
  return {
    items,
    partial: false,
    failedLocations: [],
    asOf: iso(0),
    blockedCounts,
    kpis: {
      groups: items.length,
      eligible: items.filter(r => r.enableAllowed).length,
      blocked: items.filter(r => !r.enableAllowed).length,
      potentialMonthlySavings: priced.reduce((a, r) => a + (r.monthlySavings ?? 0), 0),
      savingsUnknownGroups: items.filter(r => r.savingsUnknown).length,
    },
  };
}

export function demoPotential(provider: DemoProvider = 'AWS'): Potential {
  const items = seedsOf(provider).map(rowFrom);
  let total = 0;
  let priced = 0;
  let unpriced = 0;
  let ineligible = 0;
  for (const r of items) {
    if (r.externalSpot || !(r.managed || r.enableAllowed)) ineligible += 1;
    else if (r.savingsUnknown || r.monthlySavings === null) unpriced += 1;
    else {
      total += r.monthlySavings;
      priced += 1;
    }
  }
  return {
    potentialMonthlySavings: priced > 0 ? Math.round(total * 100) / 100 : null,
    pricedGroups: priced,
    unpricedGroups: unpriced,
    ineligibleGroups: ineligible,
    partial: false,
    asOf: iso(0),
  };
}

const findSeed = (groupId: string): Seed | undefined =>
  [...SEEDS, ...GCP_SEEDS].find(s => (s.id ?? `demo-grp-${s.name}`) === groupId || keyOf(s) === groupId);

export function demoGroupDetail(groupId: string): GroupDetail | null {
  const seed = findSeed(groupId);
  if (!seed) return null;
  const row = rowFrom(seed);
  const active = row.state === 'ACTIVE';
  return {
    group: row,
    configMode: active ? 'MANAGED' : null,
    driftStatus: active ? 'NONE' : null,
    lastErrorCode: null,
    transitions: active
      ? [
          { fromState: 'PLANNED', toState: 'APPLYING', reasonCode: 'CONFIRMED', actorType: 'USER', operationId: 'demo-op-seed', occurredAt: iso(-9 * DAY) },
          { fromState: 'APPLYING', toState: 'ACTIVE', reasonCode: 'APPLIED', actorType: 'SYSTEM', operationId: 'demo-op-seed', occurredAt: iso(-9 * DAY + 20 * 60_000) },
        ]
      : [],
    currentOperation: null,
  };
}

// ---------------------------------------------------------------------------
// Plans and operations
// ---------------------------------------------------------------------------
const PRICE_SOURCE = { source: 'AWS Price List', version: 'demo', asOf: '2026-11-01' };
const GCP_PRICE_SOURCE = { source: 'gcp-billing-catalog', version: 'gcp-sku-match-v1', asOf: '2026-11-01' };

const plans = new Map<string, { groupId: string | null; kind: string }>();
let planSeq = 0;

/** Creates a plan (all three profiles priced, FR-PLN-02) and remembers it for the read and the confirm. */
export function demoCreatePlan(groupId: string | null, kind: string): Plan {
  planSeq += 1;
  const planId = `demo-plan-${planSeq}`;
  plans.set(planId, { groupId, kind });
  return buildPlan(planId, groupId, kind);
}

export function demoGetPlan(planId: string): Plan | null {
  const p = plans.get(planId);
  return p ? buildPlan(planId, p.groupId, p.kind) : null;
}

/** The GCP plan body: the two-version MIG floor (design-gcp.md 5.2), Option A, no pre-GA native mix. */
function gcpBody(seed: Seed): string {
  const t = seed.desired ?? 4;
  const floorN = Math.max(1, Math.min(Math.ceil(t / 2), t - 1));
  const regional = !/-[a-z]$/.test(seed.location);
  const zones = regional ? [`${seed.location}-a`, `${seed.location}-b`, `${seed.location}-c`] : [seed.location];
  const template = `https://www.googleapis.com/compute/v1/projects/${GCP_PROJECT}/global/instanceTemplates/${seed.name}-tpl`;
  const surge = regional ? zones.length : 1;
  return JSON.stringify({
    current: {
      shape: 'MIG', locationType: regional ? 'REGIONAL' : 'ZONAL', location: seed.location, name: seed.name,
      instanceTemplate: template, versions: [{ instanceTemplate: template }], targetSize: t,
      updatePolicy: { type: 'OPPORTUNISTIC', minimalAction: 'REPLACE' }, distributionZones: regional ? zones : [],
      autoscaled: false,
    },
    target: {
      shape: 'MIG', floorMechanism: 'TWO_VERSION_MIG',
      versions: [
        { name: 'xas-standard', instanceTemplate: template, targetSize: { fixed: floorN } },
        { name: 'xas-spot', instanceTemplate: '(XamOps Spot copy, created at enable)', remainder: true },
      ],
      updatePolicy: { type: 'PROACTIVE', minimalAction: 'REPLACE', mostDisruptiveAllowedAction: 'REPLACE', replacementMethod: 'SUBSTITUTE', maxSurge: { fixed: surge }, maxUnavailable: { fixed: 0 } },
    },
    diff: [
      { field: 'versions', from: '1', to: '2' },
      { field: 'standardFloor', from: null, to: String(floorN) },
    ],
    onDemandFloor: {
      mechanism: 'TWO_VERSION_MIG', profile: 'CONSERVATIVE', floorN, fullSpot: false,
      atCurrent: { standard: floorN, spot: t - floorN }, atAutoscalerMin: null, atAutoscalerMax: null, nativeMixUsed: false,
    },
    feeBasis: { contractId: null, reason: 'NO_CONTRACT' },
    revertPath: { program: 'REVERT', method: 'RESTORE_SNAPSHOT', exact: true },
    rollout: { method: 'SURGE_FIRST_PROACTIVE_REPLACE', instancesToReplace: t - floorN, surge, durationRange: '5 min to 1 h', headroom: `${surge} extra VM(s) above the target size during the rollout` },
    estimates: [
      { profile: 'conservative', monthlySavings: 260, priceSources: [GCP_PRICE_SOURCE], label: 'Estimate' },
      { profile: 'balanced', monthlySavings: 390, priceSources: [GCP_PRICE_SOURCE], label: 'Estimate' },
      { profile: 'aggressive', monthlySavings: 520, priceSources: [GCP_PRICE_SOURCE], label: 'Estimate' },
    ],
  });
}

function buildPlan(planId: string, groupId: string | null, kind: string): Plan {
  const seed = groupId ? findSeed(groupId) : undefined;
  const desired = seed?.desired ?? 4;
  if (seed?.project) {
    return {
      planId, groupId, kind, status: 'OPEN', version: 1, liveFingerprint: 'demo-fingerprint', body: gcpBody(seed),
      requiresFullSpotOptin: false, warningIds: '[]', dryRun: false, expiresAt: iso(15 * 60_000), createdAt: iso(0), batchId: null,
    };
  }
  return {
    planId,
    groupId,
    kind,
    status: 'OPEN',
    version: 1,
    liveFingerprint: 'demo-fingerprint',
    body: JSON.stringify({
      current: { desired, onDemandBaseCapacity: desired, spotPercentage: 0 },
      target: { onDemandBaseCapacity: 1, spotPercentage: 70 },
      diff: [
        { field: 'onDemandBaseCapacity', from: String(desired), to: '1' },
        { field: 'onDemandPercentageAboveBaseCapacity', from: '100', to: '30' },
      ],
      onDemandFloor: { baseCapacity: 1, onDemandCount: 2 },
      feeBasis: { contractId: null, reason: 'NO_CONTRACT' },
      revertPath: { program: 'REVERT', method: 'RESTORE_SNAPSHOT', exact: true },
      rollout: { method: 'REFRESH', instancesToReplace: desired, surge: 1, durationRange: '10-20 min', headroom: 'ok' },
      estimates: [
        { profile: 'conservative', monthlySavings: 300, priceSources: [PRICE_SOURCE], label: 'Estimate' },
        { profile: 'balanced', monthlySavings: 450, priceSources: [PRICE_SOURCE], label: 'Estimate' },
        { profile: 'aggressive', monthlySavings: 600, priceSources: [PRICE_SOURCE], label: 'Estimate' },
      ],
    }),
    requiresFullSpotOptin: false,
    warningIds: '[]',
    dryRun: false,
    expiresAt: iso(15 * 60_000),
    createdAt: iso(0),
    batchId: null,
  };
}

interface DemoOp {
  groupKey: string | null;
  groupId: string | null;
  kind: string;
  polls: number;
}
const ops = new Map<string, DemoOp>();
let opSeq = 0;

/** A confirm starts a scripted operation. `null` when the plan is unknown. */
export function demoConfirm(planId: string): { operationId: string; status: string } | null {
  const plan = plans.get(planId);
  if (!plan) return null;
  opSeq += 1;
  const operationId = `demo-op-${opSeq}`;
  const seed = plan.groupId ? findSeed(plan.groupId) : undefined;
  ops.set(operationId, { groupKey: seed ? keyOf(seed) : null, groupId: plan.groupId, kind: plan.kind, polls: 0 });
  return { operationId, status: 'QUEUED' };
}

const PHASES = ['SNAPSHOT', 'APPLY_POLICY', 'ROLLOUT', 'VERIFY'];

/** Each read advances the operation: QUEUED, RUNNING through its steps, then SUCCEEDED (group ACTIVE or REVERTED). */
export function demoOperation(operationId: string): Operation | null {
  const op = ops.get(operationId);
  if (!op) return null;
  op.polls += 1;
  const done = op.polls > PHASES.length;
  if (done && op.groupKey) stateOverride.set(op.groupKey, op.kind === 'REVERT' ? 'REVERTED' : 'ACTIVE');
  const step = Math.min(op.polls, PHASES.length);
  return {
    operationId,
    groupId: op.groupId,
    kind: op.kind,
    status: done ? 'SUCCEEDED' : op.polls === 1 ? 'QUEUED' : 'RUNNING',
    phase: 'FORWARD',
    step,
    waitReason: null,
    progress: JSON.stringify({ step: PHASES[Math.max(step - 1, 0)], total: PHASES.length }),
    errorCode: null,
    errorMessage: null,
    dryRun: false,
    deadlineAt: iso(30 * 60_000),
    finishedAt: done ? iso(0) : null,
  };
}

// ---------------------------------------------------------------------------
// Commitments
// ---------------------------------------------------------------------------
let settings: CommitmentSettings = { respectRiSpCommitments: true, riSpUtilizationThreshold: 75 };
export const demoCommitmentCoverage = demoAutoSpottingRispCoverage;
export const demoCommitmentSettings = (): CommitmentSettings => ({ ...settings });
export function saveDemoCommitmentSettings(next: Partial<CommitmentSettings>): CommitmentSettings {
  settings = { ...settings, ...next };
  return { ...settings };
}

// ---------------------------------------------------------------------------
// Analytics and activity
// ---------------------------------------------------------------------------
const figures = (spot: number, failed: number, causes: Record<string, number>, interrupted: number, rebalance: number): AnalyticsFigures => ({
  spotLaunches: spot,
  launchFailures: failed,
  failuresByCause: causes,
  interruptions: interrupted,
  rebalanceRecommendations: rebalance,
  successRate: spot + failed === 0 ? null : Math.round((spot * 1000) / (spot + failed)) / 10,
});

export function demoAnalytics(from: string, to: string, provider: DemoProvider = 'AWS'): Analytics {
  if (provider === 'GCP') {
    const web = figures(22, 1, { SPOT_CAPACITY: 1 }, 2, 0);
    return {
      from: `${from}T00:00:00Z`,
      to: `${to}T23:59:59Z`,
      notTracked: [],
      totals: web,
      groups: [
        {
          groupId: 'demo-grp-gcp-api', groupKey: `${GCP_PROJECT}/us-central1/api-mig`, name: 'api-mig', location: 'us-central1', figures: web,
          byType: [{ instanceType: 'c2-standard-8', figures: web }],
        },
      ],
    };
  }
  const api = figures(46, 2, { SPOT_CAPACITY: 2 }, 3, 5);
  const worker = figures(18, 1, { SPOT_QUOTA: 1 }, 1, 2);
  const sum = figures(64, 3, { SPOT_CAPACITY: 2, SPOT_QUOTA: 1 }, 4, 7);
  return {
    from: `${from}T00:00:00Z`,
    to: `${to}T23:59:59Z`,
    notTracked: [],
    totals: sum,
    groups: [
      {
        groupId: 'demo-grp-api', groupKey: 'us-east-1/api-asg', name: 'api-asg', location: 'us-east-1', figures: api,
        byType: [
          { instanceType: 'c5.xlarge', figures: figures(30, 1, { SPOT_CAPACITY: 1 }, 2, 3) },
          { instanceType: 'c5a.xlarge', figures: figures(16, 1, { SPOT_CAPACITY: 1 }, 1, 2) },
        ],
      },
      {
        groupId: 'demo-grp-worker', groupKey: 'eu-west-1/worker-asg', name: 'worker-asg', location: 'eu-west-1', figures: worker,
        byType: [{ instanceType: 'm5.xlarge', figures: worker }],
      },
    ],
  };
}

export function demoAudit(provider: DemoProvider = 'AWS'): AuditEntry[] {
  const row = (id: number, ago: number, eventType: string, action: string, outcome: string, groupKey: string | null, actorType = 'USER'): AuditEntry => ({
    id,
    occurredAt: iso(-ago),
    eventType,
    actorType,
    actorRole: actorType === 'USER' ? 'ROLE_USER' : null,
    operationId: null,
    planId: null,
    groupId: null,
    groupKey,
    action,
    outcome,
    errorCode: null,
  });
  if (provider === 'GCP') {
    const key = `${GCP_PROJECT}/us-central1/api-mig`;
    return [
      row(3, 6 * DAY, 'STATE_TRANSITION', 'ENABLE', 'OK', key, 'SYSTEM'),
      row(2, 6 * DAY + 600_000, 'PLAN_CONFIRMED', 'ENABLE', 'OK', key),
      row(1, 6 * DAY + 1_200_000, 'PLAN_CREATED', 'ENABLE', 'OK', key),
    ];
  }
  return [
    row(5, 2 * HOUR, 'SETTING_CHANGED', 'RISP_RESPECT', 'OK', null),
    row(4, 1 * DAY, 'DRY_RUN', 'ENABLE', 'WOULD_DO', 'us-east-1/web-asg'),
    row(3, 9 * DAY, 'STATE_TRANSITION', 'ENABLE', 'OK', 'us-east-1/api-asg', 'SYSTEM'),
    row(2, 9 * DAY + 600_000, 'PLAN_CONFIRMED', 'ENABLE', 'OK', 'us-east-1/api-asg'),
    row(1, 9 * DAY + 1_200_000, 'PLAN_CREATED', 'ENABLE', 'OK', 'us-east-1/api-asg'),
  ];
}

// ---------------------------------------------------------------------------
// Operator views (/api/xamops/autospotting-admin)
// ---------------------------------------------------------------------------
const switchRows = new Map<string, OperatorSwitch>(
  ['GLOBAL', 'AWS', 'GCP', 'AWS_CONVERSIONS', 'GCP_CONVERSIONS'].map(k => [
    k,
    { scopeKey: k, propertyValue: k.endsWith('CONVERSIONS') ? false : true, engaged: false, effectiveValue: null, reason: null, changedBy: null, changedAt: null, version: 0 },
  ]),
);

export const demoSwitches = (): OperatorSwitch[] => Array.from(switchRows.values());

export function setDemoSwitch(scopeKey: string, change: { engaged: boolean; expectedVersion: number; reason: string }): OperatorSwitch | null {
  const cur = switchRows.get(scopeKey);
  if (!cur) return null;
  const next: OperatorSwitch = {
    ...cur,
    engaged: change.engaged,
    reason: change.reason,
    changedBy: 'demo operator',
    changedAt: iso(0),
    version: cur.version + 1,
  };
  switchRows.set(scopeKey, next);
  return next;
}

export const demoOperatorOperations = (): OperatorOperations => ({
  operations: [
    {
      operationId: 'demo-op-stuck-1',
      groupId: 'demo-grp-worker',
      kind: 'ENABLE',
      status: 'WAITING',
      phase: 'FORWARD',
      step: 2,
      waitReason: 'CLOUD_QUOTA',
      progress: null,
      errorCode: 'SPOT_QUOTA',
      errorMessage: 'Spot vCPU quota reached in eu-west-1.',
      dryRun: false,
      deadlineAt: iso(2 * HOUR),
      finishedAt: null,
    },
  ],
  page: 0,
  size: 50,
  totalElements: 1,
  totalPages: 1,
});

/** Resets every demo-session change (used by tests). */
export function resetAutoSpottingDemo(): void {
  stateOverride.clear();
  ops.clear();
  opSeq = 0;
  plans.clear();
  planSeq = 0;
  settings = { respectRiSpCommitments: true, riSpUtilizationThreshold: 75 };
  switchRows.forEach((v, k) => switchRows.set(k, { ...v, engaged: false, reason: null, changedBy: null, changedAt: null, version: 0 }));
}
