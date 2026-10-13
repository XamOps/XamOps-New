import { describe, expect, it } from 'vitest';
import {
  COST_SOURCE_LABELS,
  aggregateWorkloadRequests,
  clusterCostSource,
  clusterNodes,
  clusterRequestTotals,
  dashboardReplicas,
  healthSignals,
  hpaConflict,
  hpasFor,
  isEmptyDashboard,
  monitoringState,
  namespaceDeploymentCounts,
  nodeGroups,
  nodeUsageByName,
  overallHealth,
  podCounts,
  podWorkload,
  qosChange,
  qosClassOf,
  unallocatedCapacityCost,
  workloadKey,
  workloadRows,
  workloadSavings,
  type ClusterPod,
} from '../k8sDerivations';
import { toVerticalEngineView } from '../../api/verticalRecommendationsAdapter';
import type { CapabilitiesCluster } from '../../types/k8sAutomation';
import type { EksDashboard } from '../../types/eksTypes';
import type { VerticalWorkloadWire } from '../../types/verticalRecommendations';

const dash = (d: Record<string, unknown>) => d as unknown as EksDashboard;

/** The backend's empty shell for a cluster nothing reports on (EksDashboardService.createEmptyDashboard). */
const ZERO_SHELL = dash({
  clusterOverview: { totalNodes: 0, totalPods: 0, totalNamespaces: 0, cpuUsagePercent: 0, memoryUsagePercent: 0 },
  costMetrics: { dailyCost: 0, monthlyCost: 0, nodeCosts: [] },
  securityMetrics: { totalEvents: 0, eventsByPriority: {}, alerts: [] },
  resourceUtilization: { nodes: [] },
  workloadHealth: { pods: [], deployments: [], clusterHealthScore: 100 },
});

function cluster(status: CapabilitiesCluster['agent']['status'], registered = true): CapabilitiesCluster {
  return {
    clusterName: 'c', registered,
    connection: { accessLevel: 'READ_WRITE', status: 'CONNECTED' },
    writeGate: { allowed: true, reasons: [] },
    dataSourceMode: 'agent',
    agent: { status, lastDataAt: '2026-10-11T10:00:00Z', version: 'v2' },
    automationAgent: { status: 'NOT_INSTALLED', lastSeenAt: null, version: null },
    nodeAutomation: { enabled: false, mode: null, evictorAnalyseOnly: null, derivedState: 'NOT_INSTALLED' },
    dataSources: [],
  };
}

const node = (name: string, extra: Record<string, unknown> = {}) => ({
  name,
  conditions: [{ type: 'Ready', status: 'True' }],
  allocatableCpu: '1930m',
  allocatableMemory: '3372960Ki',
  instanceType: 't3.medium',
  zone: 'ap-south-1a',
  ...extra,
});

const pod = (name: string, extra: Record<string, unknown> = {}) => ({
  name, namespace: 'team-a', phase: 'Running', nodeName: 'n1', qosClass: 'Burstable',
  controllerKind: 'ReplicaSet', controllerName: 'api-6d8f7b9c4',
  containers: [{ name: 'api', cpuRequest: '100m', memoryRequest: '128Mi', cpuLimit: '200m', memoryLimit: '256Mi' }],
  ...extra,
});

describe('monitoring state (§8.1, D10)', () => {
  it('reads the all-zeros shell with clusterHealthScore 100 as M0, by either source', () => {
    expect(isEmptyDashboard(ZERO_SHELL)).toBe(true);
    expect(monitoringState({ capabilitiesStatus: 'error', cluster: null, dashboard: ZERO_SHELL }).state).toBe('M0');
    expect(monitoringState({ capabilitiesStatus: 'ready', cluster: null, dashboard: ZERO_SHELL })).toEqual({
      state: 'M0', basis: 'capabilities', lastDataAt: null,
    });
  });

  it('lets capabilities decide when they loaded', () => {
    const full = dash({ clusterOverview: { totalNodes: 3 } });
    expect(monitoringState({ capabilitiesStatus: 'ready', cluster: null, dashboard: full }).state).toBe('M0');
    expect(monitoringState({ capabilitiesStatus: 'ready', cluster: cluster('NOT_INSTALLED'), dashboard: full }).state).toBe('M0');
    expect(monitoringState({ capabilitiesStatus: 'ready', cluster: cluster('CONNECTED', false), dashboard: full }).state).toBe('M0');
    expect(monitoringState({ capabilitiesStatus: 'ready', cluster: cluster('STALE'), dashboard: full })).toMatchObject({
      state: 'M2', lastDataAt: '2026-10-11T10:00:00Z',
    });
    expect(monitoringState({ capabilitiesStatus: 'ready', cluster: cluster('CONNECTED'), dashboard: ZERO_SHELL }).state).toBe('M1');
  });

  it('falls back to the dashboard while capabilities load, fail, or report UNKNOWN', () => {
    const full = dash({ k8sNodes: [node('n1')] });
    expect(monitoringState({ capabilitiesStatus: 'loading', cluster: null, dashboard: full })).toMatchObject({ state: 'M1', basis: 'dashboard' });
    expect(monitoringState({ capabilitiesStatus: 'ready', cluster: cluster('UNKNOWN'), dashboard: full }).state).toBe('M1');
    expect(monitoringState({ capabilitiesStatus: 'error', cluster: null, dashboard: null }).state).toBe('M0');
  });
});

describe('health signals (D10)', () => {
  it('counts ready nodes, pressure, pods and unschedulable pods, each from its own data', () => {
    const d = dash({
      k8sNodes: [
        node('n1'),
        node('n2', { conditions: [{ type: 'Ready', status: 'False' }, { type: 'MemoryPressure', status: 'True' }] }),
        node('n3', { conditions: [{ type: 'Ready', status: 'True' }, { type: 'DiskPressure', status: 'True' }] }),
      ],
      k8sPods: [
        pod('a'),
        pod('b', { phase: 'Failed' }),
        pod('c', { containers: [{ name: 'x', state: 'waiting', waitingReason: 'CrashLoopBackOff' }] }),
        pod('d', { phase: 'Pending', nodeName: '', unschedulable: true }),
        pod('e', { phase: 'Pending', nodeName: '' }),
      ],
    });
    const byId = Object.fromEntries(healthSignals(d).map((s) => [s.id, s]));
    expect(byId['nodes-ready']).toMatchObject({ value: '2 / 3', attention: true, detail: '1 node not ready' });
    expect(byId['node-pressure']).toMatchObject({ value: '2 nodes', attention: true, detail: 'Under memory, disk pressure' });
    expect(byId.pods).toMatchObject({ value: '2 / 5', attention: true, detail: '1 failed · 1 container crash-looping' });
    expect(byId.unschedulable).toMatchObject({ value: '1', attention: true, detail: '1 pending, schedulable' });
    expect(overallHealth('M1', healthSignals(d))).toEqual({ kind: 'attention', label: 'Needs attention (4)', attentionCount: 4 });
  });

  it('reads a calm cluster as Healthy and an empty one as unknown, never as zeros', () => {
    const calm = dash({ k8sNodes: [node('n1')], k8sPods: [pod('a')] });
    expect(overallHealth('M1', healthSignals(calm))).toMatchObject({ kind: 'healthy', label: 'Healthy' });
    const signals = healthSignals(ZERO_SHELL);
    expect(signals.every((s) => !s.known && s.value === '—')).toBe(true);
    expect(overallHealth('M0', signals).label).toBe('Not monitored');
    expect(overallHealth('M2', healthSignals(calm)).label).toBe('Data stale');
    expect(overallHealth(null, signals).label).toBe('Unknown');
  });

  it('falls back to nodeMetrics and resourceUtilization node shapes', () => {
    const fromMetrics = dash({ nodeMetrics: { nodes: [{ name: 'm1', ready: true }, { name: 'm2', ready: false, diskPressure: true }] } });
    expect(clusterNodes(fromMetrics).map((n) => [n.name, n.ready, n.pressure.disk])).toEqual([['m1', true, false], ['m2', false, true]]);
    const fromUtil = dash({ resourceUtilization: { nodes: [{ nodeName: 'u1', condition: 'Ready' }, { nodeName: 'u2', condition: 'Unknown' }] } });
    expect(clusterNodes(fromUtil).map((n) => n.ready)).toEqual([true, null]);
  });

  it('counts running against total from one source, so running never exceeds the total', () => {
    // The old cards showed "52/18": the overview's running count over the pod list's length.
    const mixed = dash({
      clusterOverview: { runningPods: 52, totalPods: 60 },
      k8sPods: [pod('a'), pod('b'), pod('c', { phase: 'Pending', nodeName: '' }), pod('d', { phase: 'Failed' })],
    });
    expect(podCounts(mixed)).toEqual({ running: 2, total: 4, pending: 1, failed: 1, basis: 'pods' });
    expect(healthSignals(mixed).find((s) => s.id === 'pods')?.value).toBe('2 / 4');
    // Without a pod list, the overview's own pair (the agent counts both from one list).
    expect(podCounts(dash({ clusterOverview: { runningPods: 5, totalPods: 7, pendingPods: 2 } })))
      .toEqual({ running: 5, total: 7, pending: 2, failed: 0, basis: 'overview' });
    // Half a pair is no pair.
    expect(podCounts(dash({ clusterOverview: { runningPods: 52 } }))).toBeNull();
    expect(podCounts(ZERO_SHELL)).toBeNull();
  });
});

describe('workloads (§10.4)', () => {
  const d = dash({
    k8sDeployments: [
      { name: 'api', namespace: 'team-a', replicas: 2, readyReplicas: 2 },
      { name: 'web', namespace: 'team-a', replicas: 1, readyReplicas: 0 },
      { name: 'api', namespace: 'team-b', replicas: 1, readyReplicas: 1 },
    ],
    k8sStatefulSets: [{ name: 'db', namespace: 'team-a', replicas: 1, readyReplicas: 1 }],
    k8sDaemonSets: [{ name: 'agent', namespace: 'kube-system', desiredNumberScheduled: 2, numberReady: 2 }],
    k8sPods: [
      pod('api-6d8f7b9c4-aaaaa'),
      pod('api-6d8f7b9c4-bbbbb', { containers: [
        { name: 'api', cpuRequest: '100m', memoryRequest: '128Mi' },
        { name: 'sidecar' },
      ] }),
      pod('web-5f7d9c8b6-ccccc', { controllerName: 'web-5f7d9c8b6', phase: 'Pending' }),
      pod('db-0', { controllerKind: 'StatefulSet', controllerName: 'db', containers: [{ name: 'db', cpuRequest: '1', memoryRequest: '2Gi' }] }),
      pod('agent-x1', { namespace: 'kube-system', controllerKind: 'DaemonSet', controllerName: 'agent' }),
      pod('agent-x2', { namespace: 'kube-system', controllerKind: 'DaemonSet', controllerName: 'agent' }),
    ],
    topCpuPods: [
      { name: 'api-6d8f7b9c4-aaaaa', namespace: 'team-a', cpuMillicores: 40 },
      { name: 'api-6d8f7b9c4-bbbbb', namespace: 'team-a', cpuMillicores: 60 },
      { name: 'db-0', namespace: 'team-a', cpuMillicores: 300 },
      { podName: 'agent-x1', namespace: 'kube-system', value: 0.01 },
    ],
  });

  it('aggregates requests from pod containers and counts the unset ones', () => {
    const agg = aggregateWorkloadRequests(d);
    expect(agg.get(workloadKey('team-a', 'Deployment', 'api'))).toMatchObject({
      cpuRequestM: 200, memRequestMi: 256, containers: 3, cpuUnset: 1, memUnset: 1, pods: 2,
    });
    expect(agg.get(workloadKey('team-a', 'StatefulSet', 'db'))).toMatchObject({ cpuRequestM: 1000, memRequestMi: 2048 });
    expect(agg.has(workloadKey('team-b', 'Deployment', 'api'))).toBe(false);
  });

  it('maps a ReplicaSet to its Deployment by name, preferring the longest match', () => {
    const p = { name: 'x', namespace: 'ns', owner: { kind: 'ReplicaSet', name: 'api-v2-5d6ff98784' } } as ClusterPod;
    expect(podWorkload(p, new Map([['ns', ['api', 'api-v2']]]))).toEqual({ namespace: 'ns', kind: 'Deployment', name: 'api-v2' });
    expect(podWorkload(p, new Map())).toEqual({ namespace: 'ns', kind: 'Deployment', name: 'api-v2' });
    expect(podWorkload({ ...p, owner: { kind: 'Job', name: 'j' } }, new Map())).toBeNull();
  });

  it('builds rows with usage only when every running pod is in topCpuPods, and efficiency only when both are known', () => {
    const rows = Object.fromEntries(workloadRows(d).map((r) => [r.key, r]));
    const api = rows[workloadKey('team-a', 'Deployment', 'api')];
    expect(api).toMatchObject({ kind: 'Deployment', ready: 2, desired: 2, cpuUsageM: 100 });
    // One container has no CPU request, so usage ÷ requests isn't known.
    expect(api.efficiencyPct).toBeNull();
    const db = rows[workloadKey('team-a', 'StatefulSet', 'db')];
    expect(db).toMatchObject({ cpuUsageM: 300, efficiencyPct: 30 });
    // One of two DaemonSet pods reported: usage stays unknown.
    expect(rows[workloadKey('kube-system', 'DaemonSet', 'agent')]).toMatchObject({ ready: 2, desired: 2, cpuUsageM: null });
    expect(rows[workloadKey('team-a', 'Deployment', 'web')]).toMatchObject({ ready: 0, desired: 1, cpuUsageM: null });
    expect(rows[workloadKey('team-b', 'Deployment', 'api')].requests).toBeNull();
  });

  it('counts deployments per namespace from the deployment list, not namespaces[].deploymentCount', () => {
    const counts = namespaceDeploymentCounts(dash({
      k8sDeployments: [{ name: 'a', namespace: 'x' }, { name: 'b', namespace: 'x' }, { name: 'c', namespace: 'y' }],
      namespaces: [{ name: 'x', deploymentCount: 0 }],
    }));
    expect(Object.fromEntries(counts)).toEqual({ x: 2, y: 1 });
    expect(Object.fromEntries(namespaceDeploymentCounts(dash({ workloadHealth: { deployments: [{ name: 'a', namespace: 'z' }] } }))))
      .toEqual({ z: 1 });
  });

  it('finds replicas for savings from the dashboard when the engine sends none', () => {
    expect(dashboardReplicas(d, { namespace: 'team-a', kind: 'Deployment', name: 'api' })).toBe(2);
    expect(dashboardReplicas(d, { namespace: 'team-a', kind: 'StatefulSet', name: 'db' })).toBe(1);
    expect(dashboardReplicas(d, { namespace: 'team-a', kind: 'Deployment', name: 'nope' })).toBeNull();
  });
});

describe('node groups (§10.5)', () => {
  it('groups by managed node group, then Karpenter NodePool, else Unmanaged', () => {
    const d = dash({
      k8sNodes: [
        node('m1', { labels: { 'eks.amazonaws.com/nodegroup': 'ng-1', 'eks.amazonaws.com/capacityType': 'ON_DEMAND' } }),
        node('m2', { labels: { 'eks.amazonaws.com/nodegroup': 'ng-1', 'eks.amazonaws.com/capacityType': 'SPOT' }, zone: 'ap-south-1b', instanceType: 'm5.large' }),
        node('k1', { labels: { 'karpenter.sh/nodepool': 'spot', 'karpenter.sh/capacity-type': 'spot' } }),
        node('u1', { labels: {}, capacityType: 'on-demand', allocatableCpu: undefined }),
      ],
      k8sPods: [
        pod('a', { nodeName: 'm1' }),
        pod('b', { nodeName: 'm2' }),
        pod('c', { nodeName: 'k1', phase: 'Succeeded' }),
      ],
    });
    const groups = nodeGroups(d);
    expect(groups.map((g) => [g.type, g.name, g.nodes])).toEqual([
      ['Managed node group', 'ng-1', 2],
      ['Karpenter NodePool', 'spot', 1],
      ['Unmanaged', 'Unmanaged', 1],
    ]);
    const ng = groups[0];
    expect(ng).toMatchObject({ ready: 2, onDemand: 1, spot: 1, capacityUnknown: 0, instanceTypes: ['m5.large', 't3.medium'] });
    expect(ng.zones).toEqual(['ap-south-1a', 'ap-south-1b']);
    expect(ng.allocatableCpuM).toBe(3860);
    expect(ng.cpuRequestedPct).toBeCloseTo((200 / 3860) * 100, 6);
    expect(groups[1]).toMatchObject({ spot: 1, cpuRequestedPct: 0 });
    // A node without an allocatable quantity leaves its group's allocatable unknown, not 0.
    expect(groups[2]).toMatchObject({ onDemand: 1, allocatableCpuM: null, cpuRequestedPct: null });
  });

  it('keeps per-node usage null when nothing reports it', () => {
    const usage = nodeUsageByName(dash({
      resourceUtilization: { nodes: [{ nodeName: 'n1', cpuUsagePercent: null, memoryUsagePercent: null }, { nodeName: 'n2', cpuUsagePercent: 40 }] },
      nodeUtilization: [{ nodeName: 'n3', cpuUsagePct: 11, memUsagePct: 23 }],
    }));
    expect(usage.get('n1')).toEqual({ cpuPct: null, memPct: null });
    expect(usage.get('n2')).toEqual({ cpuPct: 40, memPct: null });
    expect(usage.get('n3')).toEqual({ cpuPct: 11, memPct: 23 });
  });
});

describe('cost source order (D9)', () => {
  it('takes OpenCost, then the agent cost exporter, then the Prometheus exporter, else none', () => {
    const open = dash({
      openCost: { enabled: true, summary: { totalDailyCost: 12, totalMonthlyCost: 360 } },
      agentCostMetrics: { totalHourlyCost: 2 },
      costMetrics: { dailyCost: 99, monthlyCost: 0 },
    });
    expect(clusterCostSource(open)).toEqual({
      kind: 'opencost', label: 'OpenCost (agent)', dailyCost: 12, monthlyCost: 360, monthlyFromDaily: false,
    });
    const disabledOpenCost = dash({ openCost: { enabled: false, summary: { totalDailyCost: 12 } }, agentCostMetrics: { totalHourlyCost: 2 }, costMetrics: { dailyCost: 48 } });
    expect(clusterCostSource(disabledOpenCost)).toMatchObject({ kind: 'agent-exporter', label: COST_SOURCE_LABELS['agent-exporter'], dailyCost: 48, monthlyCost: 1440, monthlyFromDaily: true });
    expect(clusterCostSource(dash({ agentCostMetrics: { totalHourlyCost: 2 } }))).toMatchObject({ dailyCost: 48 });
    expect(clusterCostSource(dash({ costMetrics: { dailyCost: 48.6, monthlyCost: 1480.2 } }))).toEqual({
      kind: 'prometheus-exporter', label: 'XamOps cost exporter (Prometheus)', dailyCost: 48.6, monthlyCost: 1480.2, monthlyFromDaily: false,
    });
    expect(clusterCostSource(ZERO_SHELL)).toEqual({ kind: 'none', label: 'No cost source', dailyCost: null, monthlyCost: null, monthlyFromDaily: false });
  });

  it('shows unallocated capacity cost only with the CPU/RAM split and known requests and allocatable', () => {
    const d = dash({
      agentCostMetrics: { totalHourlyCost: 1, cpuHourlyCost: 0.6, memoryHourlyCost: 0.4 },
      k8sNodes: [node('n1', { allocatableCpu: '2000m', allocatableMemory: '4Gi' })],
      k8sPods: [pod('a', { containers: [{ name: 'a', cpuRequest: '500m', memoryRequest: '1Gi' }] })],
    });
    const cost = unallocatedCapacityCost(d)!;
    expect(cost.cpuIdleFraction).toBe(0.75);
    expect(cost.memIdleFraction).toBe(0.75);
    expect(cost.daily).toBeCloseTo((0.6 * 0.75 + 0.4 * 0.75) * 24, 10);
    expect(unallocatedCapacityCost(dash({ ...d, agentCostMetrics: { totalHourlyCost: 1 } }))).toBeNull();
    expect(unallocatedCapacityCost(dash({ ...d, k8sNodes: [node('n1', { allocatableCpu: undefined })] }))).toBeNull();
    expect(clusterRequestTotals(d)).toMatchObject({ cpuRequestM: 500, cpuAllocatableM: 2000, cpuRequestedPct: 25, memRequestedPct: 25 });
  });
});

// ── Vertical recommendations ─────────────────────────────────────────────────

const R = (cpu: number | null, mem: number | null, cpuLim: number | null = cpu, memLim: number | null = mem) =>
  ({ cpuRequestM: cpu, memRequestMi: mem, cpuLimitM: cpuLim, memLimitMi: memLim });

function workload(over: Partial<VerticalWorkloadWire> = {}): VerticalWorkloadWire {
  return {
    ref: { namespace: 'team-a', kind: 'Deployment', name: 'api' },
    policy: { mode: 'RECOMMEND', headroomPct: 25, percentile: 95, window: '24h', applyMethod: 'IN_PLACE' },
    replicas: 2,
    hpa: { present: false, name: null },
    gitopsManaged: null,
    skipReason: null,
    findings: [],
    containers: [{ name: 'api', qosClass: 'Burstable', current: R(1000, 1024, 2000, 2048), recommended: R(500, 512, 2000, 2048), reasons: [] }],
    lastAction: null,
    autoApplyEligible: false,
    ...over,
  };
}

const view = (...workloads: VerticalWorkloadWire[]) => toVerticalEngineView({
  engine: {
    enabled: true, ready: false, clusterAccess: null, executionPath: 'AGENT',
    executionPathDetail: { direct: { available: false, reason: null }, agent: { available: true, reason: null, lastSeenAt: null } },
    metricsSource: { reachable: true, name: 'observer' }, lastCycle: { at: null, evaluated: null, error: null },
  },
  gateReasons: [],
  workloads,
}).workloads;

describe('savings eligibility (D9)', () => {
  it('estimates decreases × replicas × 720 h at the reference rates', () => {
    const [w] = view(workload());
    const s = workloadSavings(w);
    expect(s).toMatchObject({ eligible: true, exclusion: null, replicas: 2, needsMore: false, contributing: 1 });
    expect(s.monthlyUsd).toBeCloseTo(((500 / 1000) * 0.0416 + (512 / 1024) * 0.00456) * 2 * 720, 10);
  });

  it.each([
    ['skipped', { skipReason: 'LOCKED' }],
    ['requests-unset', { findings: ['REQUESTS_UNSET'] }],
    ['below-floor', { containers: [{ name: 'api', qosClass: 'Burstable', current: R(100, 512), recommended: R(10, 100), reasons: [] }] }],
    ['invalid-value', { containers: [{ name: 'api', qosClass: 'Burstable', current: R(0, 512), recommended: R(50, 300), reasons: [] }] }],
    ['hpa-conflict', { findings: ['HPA_COEXISTENCE'] }],
    ['replicas-unknown', { replicas: null }],
  ])('excludes %s', (exclusion, over) => {
    const [w] = view(workload(over as Partial<VerticalWorkloadWire>));
    expect(workloadSavings(w)).toMatchObject({ eligible: false, exclusion, monthlyUsd: 0 });
  });

  it('falls back to dashboard replicas, flags increases separately, and ignores "no change recommended"', () => {
    const [w] = view(workload({
      replicas: null,
      containers: [
        { name: 'up', qosClass: 'Burstable', current: R(100, 256), recommended: R(300, 256), reasons: [] },
        { name: 'same', qosClass: 'Burstable', current: R(100, 256), recommended: null, reasons: ['WITHIN_HYSTERESIS'] },
        { name: 'unset', qosClass: 'BestEffort', current: R(null, null), recommended: R(50, 300), reasons: [] },
      ],
    }));
    const s = workloadSavings(w, { replicasFallback: 3 });
    expect(s).toMatchObject({ eligible: true, replicas: 3, needsMore: true, contributing: 0, monthlyUsd: 0 });
    expect(workloadSavings(view(workload())[0], { hpaConflict: true }).exclusion).toBe('hpa-conflict');
  });

  it('detects an HPA that scales on CPU while the CPU request changes without the engine hold', () => {
    const hpas = hpasFor(dash({ k8sHpas: [
      { name: 'api', namespace: 'team-a', targetKind: 'Deployment', targetName: 'api', cpuTargetPct: 50 },
      { name: 'other', namespace: 'team-a', targetKind: 'Deployment', targetName: 'web', cpuTargetPct: 50 },
    ] }), { namespace: 'team-a', kind: 'Deployment', name: 'api' });
    expect(hpas.map((h) => h.name)).toEqual(['api']);
    const [changing] = view(workload());
    expect(hpaConflict(changing, hpas)).toEqual({
      hpaName: 'api', cpuTargetPct: 50,
      message: 'HPA api scales on CPU at 50% of requests. Changing requests moves its scaling point.',
    });
    const [held] = view(workload({ containers: [{ name: 'api', qosClass: 'Burstable', current: R(1000, 1024), recommended: R(1000, 512), reasons: ['HPA_CPU_MANAGED'] }] }));
    expect(hpaConflict(held, hpas)).toBeNull();
    expect(hpaConflict(changing, [{ name: 'mem', cpuTargetPct: 0 }])).toBeNull();
  });
});

describe('QoS change (D16)', () => {
  it('classifies containers and detects a changed class', () => {
    expect(qosClassOf(R(500, 512))).toBe('Guaranteed');
    expect(qosClassOf(R(500, 512, 1000, 1024))).toBe('Burstable');
    expect(qosClassOf(R(null, null, null, null))).toBe('BestEffort');
    expect(qosChange('Guaranteed', R(1000, 1024), R(500, 512, 1000, 1024))).toEqual({ from: 'Guaranteed', to: 'Burstable' });
    expect(qosChange('Guaranteed', R(1000, 1024), R(500, 512))).toBeNull();
    expect(qosChange(null, R(1000, 1024, 2000, 2048), R(500, 512, 2000, 2048))).toBeNull();
    expect(qosChange('Burstable', R(100, 100, 200, 200), null)).toBeNull();
  });
});
