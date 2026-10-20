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
      // Throttled: the request goes up and the limit moves with it. Priced on-demand (real quote).
      w('production', 'Deployment', 'api-server', {
        replicas: 3, findings: ['THROTTLED'],
        containers: [{ name: 'api-server', qosClass: 'Burstable', current: res(256, 512, 512, 1024), recommended: res(400, 512, 800, 1024), reasons: ['LIMIT_MOVED'] }],
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
      // Every container within hysteresis: no change recommended.
      w('monitoring', 'Deployment', 'grafana', {
        containers: [{ name: 'grafana', qosClass: 'Burstable', current: res(100, 256, 300, 512), recommended: null, reasons: ['WITHIN_HYSTERESIS'] }],
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
      // A CPU decrease; memory is already under the floor and isn't raised without demand. Unpriced:
      // the node type isn't known, so the savings cell falls to a dash + reason (§3.5 fail-honest).
      w('monitoring', 'StatefulSet', 'alertmanager', {
        containers: [{ name: 'alertmanager', qosClass: 'Burstable', current: res(50, 128, 200, 256), recommended: res(30, 128, 200, 256), reasons: ['BELOW_FLOOR_NO_RAISE'] }],
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
