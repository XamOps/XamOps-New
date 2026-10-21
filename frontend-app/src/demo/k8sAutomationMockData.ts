/**
 * Demo fixtures for CloudK8s automation (docs/cloudk8s/ui-revamp-design.md §16).
 *
 * Demo-only and never reachable outside a demo session. Shapes follow the real endpoints; timestamps
 * are generated relative to now so freshness labels stay current.
 *
 * - `demo-aws-001` is read-write and the demo user is permitted, as a real permitted customer would
 *   see it; the UI adds DEMO itself, so its node-automation drafts are editable and every save reads
 *   "Demo workspace. Changes aren't saved."
 * - `demo-aws-002` is a read-only connection: every control reads "Read-only connection…" except
 *   where a product rule ranks higher (§6.4).
 * - Both: readiness as in production (all not ready), the vertical engine switched on, and the same
 *   clusters. `staging-eks-cluster` has no entry, so it reads as unregistered.
 */
import type {
  AutomationDashboardDto,
  AutomationNodeFleet,
  AutomationRebalanceStatus,
  CapabilitiesCluster,
  CapabilitiesDto,
  CapabilitiesReadiness,
  K8sRefusal,
  ReadinessKey,
} from '../types/k8sAutomation';
import type { VerticalStatusWire, VerticalWorkloadWire } from '../types/verticalRecommendations';
import type { ActivityEntryWire, ActivityPageWire } from '../types/automationActivity';
import { READINESS_KEYS } from '../types/k8sAutomation';

/** The §6.7 messages, as the server sends them while a key is not ready. */
export const DEMO_READINESS_MESSAGES: Record<ReadinessKey, string> = {
  'vertical-rightsizing':
    'Workload autoscaling changes are disabled until the vertical rightsizer passes live validation. Recommendations stay visible.',
  'hpa-apply': 'Automated HPA changes are disabled until the HPA manager passes safety validation.',
  'vpa-apply': "XamOps doesn't create or update VPA objects yet.",
  'evictor-live': 'Live consolidation is disabled until pod-fit simulation is available.',
  rebalancer: 'Rebalancing is blocked: the current rebalancer can drain the same nodes repeatedly.',
  'node-optimizer-actions':
    'Node cordon, drain and spot conversion are disabled until the node optimizer passes safety validation.',
  'scale-out': "Not available: the automation agent doesn't provision nodes. Your cluster autoscaler or Karpenter does.",
  'node-templates': "Not available: the automation agent doesn't use node templates yet.",
};

/** Demo accounts the fixture answers for; anything else is refused like an account that isn't yours. */
export const DEMO_K8S_ACCOUNTS = ['demo-aws-001', 'demo-aws-002'] as const;

const ago = (now: number, seconds: number) => new Date(now - seconds * 1000).toISOString();

/**
 * When each demo activity entry happened, in seconds before now. Shared by endpoint 1's
 * `lastAction` and the activity feed, so the two always tell the same story.
 */
const DEMO_ACTIVITY_AGO = {
  apiServerPutBack: 12 * 60,
  apiStagingTestRun: 25 * 60,
  frontendRefused: 2 * 3600 + 10 * 60,
  workerHpa: 5 * 3600 + 4 * 60,
  grafanaApplied: 26 * 3600 + 13 * 60,
  alertmanagerMode: 3 * 24 * 3600 + 2 * 3600,
  apiServerLegacyFailed: 4 * 24 * 3600 + 5 * 3600,
} as const;

function readiness(): Record<string, CapabilitiesReadiness> {
  return Object.fromEntries(READINESS_KEYS.map((key) => [key, {
    ready: false,
    reason: key === 'evictor-live' ? 'ANALYSE_ONLY' : 'CONTROLLER_NOT_READY',
    message: DEMO_READINESS_MESSAGES[key],
  }]));
}

function clusters(now: number, readOnly: boolean): CapabilitiesCluster[] {
  const connection = { accessLevel: readOnly ? 'READ_ONLY' : 'READ_WRITE', status: 'CONNECTED' } as const;
  const writeGate = { allowed: !readOnly, reasons: readOnly ? ['READ_ONLY_CONNECTION'] : [] };
  return [
    {
      clusterName: 'prod-eks-cluster',
      registered: true,
      connection,
      writeGate,
      dataSourceMode: 'agent',
      agent: { status: 'CONNECTED', lastDataAt: ago(now, 4 * 60 + 12), version: 'v2.1.0' },
      automationAgent: { status: 'CONNECTED', lastSeenAt: ago(now, 40), version: '1.0.0-autoscaler' },
      nodeAutomation: { enabled: true, mode: 'full', evictorAnalyseOnly: true, derivedState: 'ANALYSIS' },
      dataSources: [
        { type: 'k8s-resources', collectedAt: ago(now, 4 * 60 + 12), items: 1, displayed: true },
        { type: 'prometheus-metrics', collectedAt: ago(now, 4 * 60 + 15), items: 1, displayed: true },
        { type: 'opencost-cost', collectedAt: ago(now, 9 * 60 + 41), items: 14, displayed: true },
        { type: 'trivy-vulnerabilities', collectedAt: ago(now, 52 * 60), items: 37, displayed: true },
        { type: 'kubescape-compliance', collectedAt: ago(now, 3 * 3600 + 7 * 60), items: 22, displayed: false },
        { type: 'autoscaling-metrics', collectedAt: ago(now, 55), items: 1, displayed: true },
      ],
    },
    {
      clusterName: 'eu-eks-cluster',
      registered: true,
      connection,
      writeGate,
      dataSourceMode: 'agent',
      agent: { status: 'CONNECTED', lastDataAt: ago(now, 2 * 60 + 48), version: 'v2.1.0' },
      automationAgent: { status: 'CONNECTED', lastSeenAt: ago(now, 1 * 60 + 5), version: '1.0.0-autoscaler' },
      nodeAutomation: { enabled: false, mode: null, evictorAnalyseOnly: null, derivedState: 'OFF' },
      dataSources: [
        { type: 'k8s-resources', collectedAt: ago(now, 2 * 60 + 48), items: 1, displayed: true },
        { type: 'prometheus-metrics', collectedAt: ago(now, 2 * 60 + 51), items: 1, displayed: true },
      ],
    },
  ];
}

/** Capabilities for a demo account, or null for an account the demo doesn't have. */
export function demoCapabilities(accountId: string, now: number = Date.now()): CapabilitiesDto | null {
  if (!(DEMO_K8S_ACCOUNTS as readonly string[]).includes(accountId)) return null;
  const readOnly = accountId === 'demo-aws-002';
  return {
    schemaVersion: 1,
    accountId,
    generatedAt: new Date(now).toISOString(),
    connection: { accessLevel: readOnly ? 'READ_ONLY' : 'READ_WRITE', status: 'CONNECTED' },
    user: { canChangeAutomation: true, reasons: [], detail: null },
    writeGate: { allowed: !readOnly, reasons: readOnly ? ['READ_ONLY_CONNECTION'] : [] },
    readiness: readiness(),
    verticalEngine: { enabled: true },
    clusters: clusters(now, readOnly),
  };
}

/** What the server answers for an account that isn't the caller's. */
export const DEMO_NO_ACCOUNT_ACCESS: K8sRefusal = {
  success: false,
  code: 'K8S_NO_ACCOUNT_ACCESS',
  message: "You don't have access to this account.",
  field: null,
  detail: null,
};

// ── Vertical engine, endpoint 1 (§16; published contract §2.1) ──────────────

/** Every automation write in a demo session (§16): refused, and nothing is sent to the backend. */
export const DEMO_WRITE_REFUSED: K8sRefusal = {
  success: false,
  code: 'K8S_DEMO',
  message: "Demo workspace. Changes aren't saved.",
  field: null,
  detail: null,
};

/** What the engine answers for a cluster XamOps doesn't have a config for. */
export const DEMO_CLUSTER_NOT_REGISTERED: K8sRefusal = {
  success: false,
  code: 'K8S_CLUSTER_NOT_REGISTERED',
  message: "This cluster isn't registered with XamOps.",
  field: null,
  detail: null,
};

type Res = { cpuRequestM: number | null; memRequestMi: number | null; cpuLimitM: number | null; memLimitMi: number | null };
const res = (cpuRequestM: number | null, memRequestMi: number | null, cpuLimitM: number | null, memLimitMi: number | null): Res =>
  ({ cpuRequestM, memRequestMi, cpuLimitM, memLimitMi });

function verticalWorkload(
  now: number,
  ref: { namespace: string; kind: string; name: string },
  over: Partial<Omit<VerticalWorkloadWire, 'ref'>> & { mode?: string },
): VerticalWorkloadWire {
  const { mode = 'RECOMMEND', ...rest } = over;
  return {
    ref,
    policy: { mode, headroomPct: 25, percentile: 95, window: '24h', applyMethod: 'IN_PLACE' },
    replicas: 1,
    hpa: { present: false, name: null },
    gitopsManaged: false,
    skipReason: null,
    findings: [],
    containers: [],
    lastAction: null,
    autoApplyEligible: false,
    provenance: {
      percentile: 95, windowHours: 24, windowStart: ago(now, 24 * 3600), windowEnd: ago(now, 300),
      sampleCoverage: 0.94, usageSource: 'prometheus', policySource: mode === 'OFF' ? 'DEFAULTS' : 'STORED',
    },
    horizontal: { state: 'NOT_EVALUATED' },
    ...rest,
  };
}

/**
 * Endpoint 1 for the demo clusters, in the contract's shapes. prod is V4 with nine workloads that
 * between them show each case the table renders; eu is V2 (no usage metrics, no workloads); any
 * other cluster isn't registered, so the router answers 404 with {@link DEMO_CLUSTER_NOT_REGISTERED}.
 */
export function demoVerticalStatus(cluster: string, now: number = Date.now()): VerticalStatusWire | null {
  const engine = (metrics: boolean): VerticalStatusWire['engine'] => ({
    enabled: true,
    ready: false,
    // The engine's own vocabulary (CONFIGURED | NOT_CONFIGURED | UNREACHABLE), not the reason code.
    clusterAccess: 'NOT_CONFIGURED',
    executionPath: 'AGENT',
    executionPathDetail: {
      direct: { available: false, reason: 'CLUSTER_ACCESS_NOT_CONFIGURED' },
      agent: { available: true, reason: null, lastSeenAt: ago(now, 40) },
    },
    metricsSource: metrics ? { reachable: true, name: 'prometheus' } : { reachable: false, name: null },
    lastCycle: { at: ago(now, 6 * 60), evaluated: metrics ? 9 : 0, error: null },
  });
  if (cluster === 'eu-eks-cluster') {
    return { engine: engine(false), gateReasons: ['CONTROLLER_NOT_READY', 'NO_METRICS_SOURCE'], workloads: [] };
  }
  if (cluster !== 'prod-eks-cluster') return null;
  const w = (namespace: string, kind: string, name: string, over: Parameters<typeof verticalWorkload>[2]) =>
    verticalWorkload(now, { namespace, kind, name }, over);
  return {
    engine: engine(true),
    gateReasons: ['CONTROLLER_NOT_READY'],
    workloads: [
      // Throttled: the request goes up and the limit moves with it. Priced on-demand (real quote). Its
      // raise was deferred twice by a full node (the activity feed): endpoint 1 reports the older,
      // pre-put-back failure, because a deferral XamOps put back changed no pod and isn't a lastAction.
      w('production', 'Deployment', 'api-server', {
        replicas: 3, findings: ['THROTTLED'],
        containers: [{ name: 'api-server', qosClass: 'Burstable', current: res(256, 512, 512, 1024), recommended: res(400, 512, 800, 1024), reasons: ['LIMIT_MOVED'] }],
        lastAction: { at: ago(now, DEMO_ACTIVITY_AGO.apiServerLegacyFailed), dryRun: false, outcome: 'FAILED' },
        cost: {
          monthlyCost: 38.4, monthlySavings: 0, reason: null, source: 'PRICE_LIST',
          capacityType: 'on-demand', capacityTypeInferred: false,
          note: 'AWS Price List on-demand rate for m5.large in us-east-1',
        },
      }),
      // An HPA scales on CPU, so the CPU request is held.
      w('production', 'Deployment', 'worker', {
        replicas: 2, hpa: { present: true, name: 'worker-hpa' }, findings: ['HPA_COEXISTENCE'],
        containers: [{ name: 'worker', qosClass: 'Burstable', current: res(100, 256, 400, 512), recommended: res(100, 320, 400, 512), reasons: ['HPA_CPU_MANAGED'] }],
      }),
      // Managed by GitOps: a persisted apply would be reverted.
      w('production', 'Deployment', 'frontend', {
        replicas: 2, gitopsManaged: true,
        containers: [{ name: 'frontend', qosClass: 'Burstable', current: res(50, 128, 200, 256), recommended: res(110, 250, 200, 256), reasons: [] }],
      }),
      // Every container within hysteresis: no change recommended. Its size is the one a user applied
      // a day ago (the activity feed's grafana entry), so endpoint 1 reports that change.
      w('monitoring', 'Deployment', 'grafana', {
        containers: [{ name: 'grafana', qosClass: 'Burstable', current: res(100, 256, 300, 512), recommended: null, reasons: ['WITHIN_HYSTERESIS'] }],
        lastAction: { at: ago(now, DEMO_ACTIVITY_AGO.grafanaApplied), dryRun: false, outcome: 'APPLIED' },
      }),
      // Idle: decreased to the safe floors. Over-provisioned with a real monthly saving.
      w('staging', 'Deployment', 'api-staging', {
        findings: ['IDLE'],
        containers: [{ name: 'api-server', qosClass: 'Burstable', current: res(100, 256, 300, 512), recommended: res(25, 250, 300, 512), reasons: ['FLOOR_CLAMPED'] }],
        cost: {
          monthlyCost: 9.12, monthlySavings: 6.84, reason: null, source: 'SPOT_HISTORY',
          capacityType: 'spot', capacityTypeInferred: false,
          note: 'EC2 spot price for m5.large in us-east-1a',
        },
      }),
      // Requests unset elsewhere; here one workload is left unpriced to show the dash + reason path.
      // Requests unset: an advisory recommendation only.
      w('staging', 'Deployment', 'worker-staging', {
        findings: ['REQUESTS_UNSET'],
        containers: [{ name: 'worker', qosClass: 'BestEffort', current: res(null, null, 200, 256), recommended: res(60, 250, 200, 256), reasons: ['REQUESTS_UNSET'] }],
      }),
      // Locked by annotation.
      w('kube-system', 'Deployment', 'coredns', {
        replicas: 2, skipReason: 'LOCKED',
        containers: [{ name: 'coredns', qosClass: 'Burstable', current: res(100, 70, null, 170), recommended: null, reasons: [] }],
      }),
      // Off: the engine's default, so it isn't evaluated.
      w('monitoring', 'StatefulSet', 'prometheus', {
        mode: 'OFF', skipReason: 'POLICY_OFF',
        containers: [{ name: 'prometheus', qosClass: 'Guaranteed', current: res(500, 2048, 1000, 4096), recommended: null, reasons: [] }],
      }),
      // A CPU decrease; memory is already under the floor and isn't raised without demand. A user
      // switched it from Off to Suggest only three days ago (the activity feed's mode change). Unpriced:
      // the node type isn't known, so the savings cell falls to a dash + reason (§3.5 fail-honest).
      w('monitoring', 'StatefulSet', 'alertmanager', {
        containers: [{ name: 'alertmanager', qosClass: 'Burstable', current: res(50, 128, 200, 256), recommended: res(30, 128, 200, 256), reasons: ['BELOW_FLOOR_NO_RAISE'] }],
        lastAction: { at: ago(now, DEMO_ACTIVITY_AGO.alertmanagerMode), dryRun: false, outcome: 'POLICY_CHANGE' },
        cost: {
          monthlyCost: null, monthlySavings: null, reason: 'UNKNOWN_INSTANCE_TYPE', source: null,
          capacityType: null, capacityTypeInferred: false, note: null,
        },
      }),
    ],
    cost: {
      currency: 'USD', hoursPerMonth: 730, monthlySavings: 6.84, monthlySavingsUnavailableReason: null,
      clusterMonthlyCost: 47.52, pricedWorkloads: 2, unpricedWorkloads: 1,
    },
  };
}

// ── Node automation reads (/api/eks/autoscaling/**), for Automation › Node autoscaler ──────────
// The shapes the backend serves (EksAutoscalingService): the dashboard with every policy at its
// default, the node fleet report and the rebalance status. prod runs node automation in analysis
// (capabilities: ANALYSIS); eu has it off. Only the reads are answered: the page sends no write.

const DEMO_NODE_CLUSTERS = ['prod-eks-cluster', 'eu-eks-cluster'] as const;

const isNodeCluster = (cluster: string): cluster is (typeof DEMO_NODE_CLUSTERS)[number] =>
  (DEMO_NODE_CLUSTERS as readonly string[]).includes(cluster);

/** `GET /api/eks/autoscaling/{cluster}`: every section, node automation on (prod) or off (eu). */
export function demoAutomationDashboard(cluster: string, now: number = Date.now()): AutomationDashboardDto | null {
  if (!isNodeCluster(cluster)) return null;
  const on = cluster === 'prod-eks-cluster';
  return {
    autoscalingEnabled: on,
    policySource: 'default',
    agentPolicyBlockedBy: null,
    agentStatus: on
      ? { mode: 'full', agentVersion: '1.0.0-autoscaler', lastSyncAt: ago(now, 40) }
      : { mode: 'disabled', agentVersion: '1.0.0-autoscaler', lastSyncAt: null },
    kpis: null,
    scaleOutPolicy: {
      enabled: true, headroomCpuPercent: 10, headroomMemoryPercent: 10, minCpuCores: 2, maxCpuCores: 32,
      minRamMib: 4096, maxRamMib: 65536, nodeReadinessTimeoutMinutes: 15,
    },
    evictorPolicy: {
      enabled: true, analyseOnly: true, aggressiveMode: false, nodeCycleIntervalSeconds: 60, nodeGracePeriodMinutes: 5,
      targetUtilizationCpu: 80, targetUtilizationMemory: 80,
    },
    workloadRightsizerPolicy: { enabled: true, applyMode: 'deferred' },
    hpaManagerPolicy: { enabled: false, analyseOnly: true, defaultMinReplicas: 1, defaultMaxReplicas: 10, defaultTargetCpuPct: 80 },
    vpaManagerPolicy: { enabled: true, analyseOnly: true, updateMode: 'Off' },
    nodeOptimizerPolicy: {
      enabled: false, idleCpuThresholdPct: 20, idleMemThresholdPct: 20, autoCordonIdleNodes: false, autoDrainIdleNodes: false,
      handleSpotInterruptions: true, spotRatioTargetPct: 0, karpenterEnabled: false, maxParallelDrains: 1,
    },
    nodeTemplates: [],
    rebalancer: {
      enabled: true, gracefulDraining: true, drainTimeoutMinutes: 20, scheduleEnabled: false, scheduleCron: '0 2 * * 0',
      lastRebalanceAt: null, lastRebalanceDuration: 0, status: 'idle',
    },
    safetyGuards: {
      respectPodDisruptionBudgets: true, protectSingleReplicas: true, protectStatefulSets: true, protectCriticalNamespaces: true,
      criticalNamespaces: ['kube-system', 'kube-public', 'kube-node-lease'],
    },
    recentEvents: [],
    recentChanges: [],
  };
}

/** `GET …/node-fleet`: prod's six nodes (3 on-demand, 3 Spot), none idle; eu's three, none idle. */
export function demoNodeFleet(cluster: string, now: number = Date.now()): AutomationNodeFleet | null {
  if (!isNodeCluster(cluster)) return null;
  return cluster === 'prod-eks-cluster'
    ? { totalNodes: 6, onDemandNodes: 3, spotNodes: 3, idleNodes: [], interruptedNodes: [], collectedAt: ago(now, 55) }
    : { totalNodes: 3, onDemandNodes: 3, spotNodes: 0, idleNodes: [], interruptedNodes: [], collectedAt: ago(now, 2 * 60 + 48) };
}

/** `GET …/rebalance-status`: what the backend serves before any rebalance has run. */
export function demoRebalanceStatus(cluster: string): AutomationRebalanceStatus | null {
  if (!isNodeCluster(cluster)) return null;
  return { status: 'idle', message: 'No rebalance has been run yet' };
}

// ── Activity feed (FEAT-007): GET /api/eks/automation/activity/{cluster} ────────────────────────
// prod-eks-cluster's own workloads, in the shapes the real xamops-test-1 history produced: a raise
// the kubelet deferred and XamOps put back (throttled-encoder), a test run, a refused apply, an HPA
// bound change (php-apache), a restart-free in-place apply (idle-batch-listener), a mode switch, and
// one row written before the activity log existed, whose "before" is the target it asked for. Every
// entry is a user's: no demo workload runs in Apply automatically. eu has no history; any other
// cluster isn't registered.

const MI = 1024 * 1024;

const sizes = (cpuRequestMillis: number | null, cpuLimitMillis: number | null, memRequestMi: number | null, memLimitMi: number | null) => ({
  cpuRequestMillis,
  cpuLimitMillis,
  memRequestBytes: memRequestMi === null ? null : memRequestMi * MI,
  memLimitBytes: memLimitMi === null ? null : memLimitMi * MI,
});

function demoActivityEntries(now: number): ActivityEntryWire[] {
  const a = DEMO_ACTIVITY_AGO;
  return [
    {
      id: 108, at: ago(now, a.apiServerPutBack), feature: 'VERTICAL', origin: 'MANUAL', who: 'A user', action: 'RESIZE',
      target: { namespace: 'production', kind: 'Deployment', name: 'api-server' },
      dryRun: false, outcome: 'NOT_CHANGED', result: 'PUT_BACK', terminal: 'DEFERRED', restartFree: true,
      change: { containers: [{ container: 'api-server', before: sizes(256, 512, 512, 1024), beforeBasis: 'OBSERVED', after: sizes(256, 512, 512, 1024), requested: sizes(400, 800, null, null) }], hpa: null, mode: null },
      proof: {
        restartFree: true,
        reverted: true,
        pods: [{
          pod: 'api-server-7d4b9c6f5-x2kfp', node: null, changed: false, uidChanged: false, restartsBefore: 0, restartsAfter: 0, ready: true,
          revert: { state: 'REVERTED', detail: 'Put back to the size it ran before, and confirmed in the pod status.' },
        }],
      },
      detail: "The kubelet deferred the resize of pod production/api-server-7d4b9c6f5-x2kfp because its node has no room for it right now (Node didn't have enough resource: cpu, requested: 400, used: 1880, capacity: 1930). Revert: REVERTED.",
      reference: '3f1c9e2a-6b7d-4e1f-9a05-c2d8b7e41a90',
    },
    {
      id: 107, at: ago(now, a.apiStagingTestRun), feature: 'VERTICAL', origin: 'MANUAL', who: 'A user', action: 'RESIZE',
      target: { namespace: 'staging', kind: 'Deployment', name: 'api-staging' },
      dryRun: true, outcome: 'TEST_RUN', result: 'TEST_RUN', terminal: 'DRY_RUN', restartFree: false,
      change: { containers: [{ container: 'api-server', before: null, beforeBasis: null, after: null, requested: sizes(25, null, 250, null) }], hpa: null, mode: null },
      proof: null,
      detail: 'Dry run: no patch was sent to the cluster, so nothing was applied.',
      reference: '7b8d4cda-1e52-4c3b-8f6a-0d9e2b17c4f3',
    },
    {
      id: 106, at: ago(now, a.frontendRefused), feature: 'VERTICAL', origin: 'MANUAL', who: 'A user', action: 'RESIZE',
      target: { namespace: 'production', kind: 'Deployment', name: 'frontend' },
      dryRun: false, outcome: 'REFUSED', result: 'REFUSED', terminal: null, restartFree: false,
      change: null, proof: null,
      detail: 'K8S_GITOPS_MANAGED: Template persistence is not available for production/Deployment/frontend: XamOps does not patch a workload template, and whether this workload\'s template is owned by Argo CD or Flux cannot be established. Use IN_PLACE, which resizes the running pods without restarting them.',
      reference: null,
    },
    {
      id: 105, at: ago(now, a.workerHpa), feature: 'HPA', origin: 'MANUAL', who: 'A user', action: 'HPA_CHANGE',
      target: { namespace: 'production', kind: 'HorizontalPodAutoscaler', name: 'worker-hpa' },
      dryRun: false, outcome: 'APPLIED', result: 'APPLIED', terminal: null, restartFree: false,
      change: { containers: null, hpa: { before: { minReplicas: 1, maxReplicas: 4, targetUtilizationPct: 60 }, after: { minReplicas: 1, maxReplicas: 5, targetUtilizationPct: 60 } }, mode: null },
      proof: null,
      detail: null,
      reference: 'c5c6b919-13c6-4af2-a5cf-7dcacf0f8c0d',
    },
    {
      id: 104, at: ago(now, a.grafanaApplied), feature: 'VERTICAL', origin: 'MANUAL', who: 'A user', action: 'RESIZE',
      target: { namespace: 'monitoring', kind: 'Deployment', name: 'grafana' },
      dryRun: false, outcome: 'APPLIED', result: 'APPLIED', terminal: 'APPLIED', restartFree: true,
      change: { containers: [{ container: 'grafana', before: sizes(250, 300, 384, 512), beforeBasis: 'OBSERVED', after: sizes(100, 300, 256, 512), requested: sizes(100, null, 256, null) }], hpa: null, mode: null },
      proof: {
        restartFree: true,
        reverted: null,
        pods: [{ pod: 'grafana-6c9f8d7b5-q4mzt', node: null, changed: true, uidChanged: false, restartsBefore: 0, restartsAfter: 0, ready: true, revert: null }],
      },
      detail: null,
      reference: '2a6f0c81-9d34-4b7e-a1c5-58e0f3d92b46',
    },
    {
      id: 103, at: ago(now, a.alertmanagerMode), feature: 'VERTICAL', origin: 'MANUAL', who: 'A user', action: 'MODE_CHANGE',
      target: { namespace: 'monitoring', kind: 'StatefulSet', name: 'alertmanager' },
      dryRun: false, outcome: 'POLICY_CHANGE', result: null, terminal: null, restartFree: false,
      change: { containers: null, hpa: null, mode: { from: 'OFF', to: 'RECOMMEND' } },
      proof: null,
      detail: 'Mode changed from OFF to RECOMMEND.',
      reference: null,
    },
    {
      // Written before the activity log: its "before" is the target it asked for, and the deferral
      // reads as the failure it was published as.
      id: 102, at: ago(now, a.apiServerLegacyFailed), feature: 'VERTICAL', origin: 'MANUAL', who: 'A user', action: 'RESIZE',
      target: { namespace: 'production', kind: 'Deployment', name: 'api-server' },
      dryRun: false, outcome: 'FAILED', result: 'FAILED', terminal: null, restartFree: true,
      change: { containers: [{ container: 'api-server', before: sizes(400, 800, null, null), beforeBasis: 'REQUESTED', after: sizes(400, 800, 512, 1024), requested: sizes(400, 800, null, null) }], hpa: null, mode: null },
      proof: null,
      detail: "Kubernetes deferred the resize: the node didn't have enough CPU (requested: 400, used: 1880, capacity: 1930).",
      reference: null,
    },
  ];
}

export interface DemoActivityQuery {
  feature: string;
  result: string;
  q: string;
  limit: string;
  cursor: string;
  /** ISO-8601 bounds; empty when not given. */
  from?: string;
  to?: string;
  /** Exactly this namespace, kind and name, each on its own; empty when not given. */
  namespace?: string;
  kind?: string;
  name?: string;
}

/**
 * `GET /api/eks/automation/activity/{cluster}` for the demo clusters, filtered and paged the way the
 * server does it (feature, result, name contains, limit, an opaque cursor), or null for a cluster the
 * demo doesn't register.
 */
export function demoActivityPage(cluster: string, query: DemoActivityQuery, now: number = Date.now()): ActivityPageWire | null {
  if (!isNodeCluster(cluster)) return null;
  const all = cluster === 'prod-eks-cluster' ? demoActivityEntries(now) : [];
  const csv = (v: string) => v.split(',').map((s) => s.trim().toUpperCase()).filter(Boolean);
  const features = csv(query.feature);
  const results = csv(query.result);
  const q = query.q.trim().toLowerCase();
  const from = query.from ? Date.parse(query.from) : NaN;
  const to = query.to ? Date.parse(query.to) : NaN;
  const exact = (wanted: string | undefined, value: string | null | undefined) => !wanted || value === wanted;
  const matching = all.filter((e) =>
    (features.length === 0 || features.includes(e.feature ?? ''))
    && (results.length === 0 || results.includes(e.result ?? ''))
    && (q === '' || (e.target?.name ?? '').toLowerCase().includes(q))
    && exact(query.namespace, e.target?.namespace) && exact(query.kind, e.target?.kind) && exact(query.name, e.target?.name)
    && (!Number.isFinite(from) || Date.parse(e.at ?? '') >= from)
    && (!Number.isFinite(to) || Date.parse(e.at ?? '') <= to));
  const parsedLimit = Number.parseInt(query.limit, 10);
  const limit = Number.isFinite(parsedLimit) && parsedLimit >= 1 && parsedLimit <= 200 ? parsedLimit : 50;
  const offsetMatch = /^demo-(\d+)$/.exec(query.cursor);
  const offset = offsetMatch ? Number(offsetMatch[1]) : 0;
  const page = matching.slice(offset, offset + limit);
  const next = offset + limit < matching.length ? `demo-${offset + limit}` : null;
  return { engine: { enabled: true }, entries: page, nextCursor: next };
}

// ── Activity summary (FEAT-005): GET /api/eks/automation/activity/{cluster}/summary ─────────────
// The same demo entries counted per Asia/Kolkata day with their own `result`, the way the server
// counts the feed. As on the server, a day before the first record, or before the first row only the
// full activity log writes (`completeSince`: a test run, a put-back, an HPA change or a resize stored
// with its proof), is left out, never zero-filled. Mode changes have no result and aren't counted.

const IST_OFFSET_MS = 5.5 * 3600 * 1000;
const istDate = (ms: number) => new Date(ms + IST_OFFSET_MS).toISOString().slice(0, 10);

type DayCounts = { applied: number; putBack: number; refused: number; testRuns: number; failed: number };

const COUNT_KEY: Record<string, keyof DayCounts> = {
  APPLIED: 'applied',
  PUT_BACK: 'putBack',
  REFUSED: 'refused',
  TEST_RUN: 'testRuns',
  FAILED: 'failed',
};

const zero = (): DayCounts => ({ applied: 0, putBack: 0, refused: 0, testRuns: 0, failed: 0 });

/** Written only by the full activity log (FEAT-005's completeSince rule). */
const fullLogRow = (e: ActivityEntryWire): boolean =>
  e.outcome === 'TEST_RUN' || e.outcome === 'NOT_CHANGED' || e.feature === 'HPA' || (e.action === 'RESIZE' && !!e.terminal);

export interface DemoSummaryAnswer {
  status: number;
  body: unknown;
}

export function demoActivitySummary(cluster: string, query: { days: string; feature: string }, now: number = Date.now()): DemoSummaryAnswer {
  if (!isNodeCluster(cluster)) return { status: 404, body: DEMO_CLUSTER_NOT_REGISTERED };
  const days = query.days === '' ? 7 : Number(query.days);
  if (!Number.isInteger(days) || days < 1 || days > 30) {
    return { status: 400, body: { ...DEMO_CLUSTER_NOT_REGISTERED, code: 'K8S_INVALID_REQUEST', message: 'days must be 1 to 30.', field: 'days' } };
  }
  const wanted = query.feature.split(',').map((s) => s.trim().toUpperCase()).filter(Boolean);
  if (wanted.some((f) => f !== 'VERTICAL' && f !== 'HPA')) {
    return { status: 400, body: { ...DEMO_CLUSTER_NOT_REGISTERED, code: 'K8S_INVALID_REQUEST', message: 'feature must be VERTICAL or HPA.', field: 'feature' } };
  }
  const features = wanted.length > 0 ? ['VERTICAL', 'HPA'].filter((f) => wanted.includes(f)) : ['VERTICAL', 'HPA'];
  const entries = (cluster === 'prod-eks-cluster' ? demoActivityEntries(now) : []).filter((e) => features.includes(e.feature ?? ''));
  const to = istDate(now);
  const from = istDate(now - (days - 1) * 24 * 3600 * 1000);
  const times = (list: ActivityEntryWire[]) => list.map((e) => Date.parse(e.at ?? '')).filter(Number.isFinite);
  const first = times(entries);
  const complete = times(entries.filter(fullLogRow));
  const firstRecordedAt = first.length > 0 ? Math.min(...first) : null;
  const completeSince = complete.length > 0 ? Math.min(...complete) : null;
  const listed: Array<DayCounts & { date: string; byFeature: Record<string, DayCounts> }> = [];
  if (firstRecordedAt !== null && completeSince !== null) {
    const startDay = [from, istDate(firstRecordedAt), istDate(completeSince)].sort().reverse()[0];
    for (let t = Date.parse(`${startDay}T00:00:00Z`); new Date(t).toISOString().slice(0, 10) <= to; t += 24 * 3600 * 1000) {
      const date = new Date(t).toISOString().slice(0, 10);
      const total = zero();
      const byFeature: Record<string, DayCounts> = Object.fromEntries(features.map((f) => [f, zero()]));
      for (const e of entries) {
        const key = COUNT_KEY[e.result ?? ''];
        if (!key || istDate(Date.parse(e.at ?? '')) !== date) continue;
        total[key] += 1;
        byFeature[e.feature ?? 'VERTICAL'][key] += 1;
      }
      listed.push({ date, ...total, byFeature });
    }
  }
  return {
    status: 200,
    body: {
      engine: { enabled: true },
      timezone: 'Asia/Kolkata',
      from,
      to,
      firstRecordedAt: firstRecordedAt !== null ? new Date(firstRecordedAt).toISOString() : null,
      completeSince: completeSince !== null ? new Date(completeSince).toISOString() : null,
      truncated: false,
      days: listed,
    },
  };
}
