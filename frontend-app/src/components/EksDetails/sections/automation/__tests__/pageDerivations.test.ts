/**
 * The derivations behind Automation › HPA, VPA, Node autoscaler and Activity (UI v3 plan §2). Each
 * number comes from a named source; what a source didn't report is null, never 0.
 */
import { describe, expect, it } from 'vitest';
import { adaptEngineResponse } from '../../../../../api/verticalRightsizerAdapter';
import { adaptHpaPage } from '../../../../../api/hpaAdapter';
import type { VerticalEngineResponseWire, VerticalResourcesWire, VerticalWorkloadWire } from '../../../../../types/verticalRightsizer';
import type { EksDashboard } from '../../../../../types/eksTypes';
import type { CapabilitiesCluster } from '../../../../../types/k8sAutomation';
import {
  activityKpis,
  agentHpas,
  automationAgentLine,
  automationAgentState,
  hpaCandidates,
  hpaConditionText,
  hpaFlags,
  hpaKpis,
  hpaRows,
  hpaWants,
  hpaWorksChecks,
  idleNodeCount,
  latestChangeRows,
  nodeBarRows,
  nodeChangers,
  nodeKpis,
  nodeLevers,
  parseHpaParam,
  rebalanceText,
  todayByResult,
  vpaKpis,
  vpaRows,
  type AgentHpa,
  SCALING_EVENT_REASON,
} from '../automationDerivations';

const NOW = Date.parse('2026-10-14T13:30:00Z');
const iso = (msAgo: number) => new Date(NOW - msAgo).toISOString();
const res = (cpuRequestM: number | null, memRequestMi: number | null): VerticalResourcesWire => ({ cpuRequestM, memRequestMi, cpuLimitM: null, memLimitMi: null });

function wire(name: string, namespace: string, over: Partial<VerticalWorkloadWire> = {}): VerticalWorkloadWire {
  return {
    ref: { namespace, kind: 'Deployment', name },
    policy: { mode: 'RECOMMEND', headroomPct: 25, percentile: 95, window: '24h', applyMethod: 'IN_PLACE' },
    replicas: 2,
    hpa: { present: false, name: null },
    gitopsManaged: false,
    skipReason: null,
    findings: [],
    containers: [{ name: 'app', qosClass: 'Burstable', current: res(100, 256), recommended: null, reasons: [], upstreamVpa: null }],
    lastAction: null,
    autoApplyEligible: false,
    provenance: { percentile: 95, windowHours: 24, windowStart: null, windowEnd: null, sampleCoverage: 0.94, usageSource: 'prometheus-proxy', policySource: 'DEFAULTS' },
    horizontal: { state: 'NO_CHANGE', minReplicas: null, maxReplicas: null, targetCpuUtilizationPct: null, reasons: [] },
    ...over,
  };
}

function body(workloads: VerticalWorkloadWire[]): VerticalEngineResponseWire {
  return {
    engine: {
      enabled: true, ready: true, clusterAccess: 'CONFIGURED', executionPath: 'DIRECT',
      executionPathDetail: { direct: { available: true, reason: null }, agent: null },
      metricsSource: { reachable: true, name: 'prometheus-proxy' },
      lastCycle: { at: iso(60_000), evaluated: workloads.length, error: null },
    },
    gateReasons: [],
    writeGatesChecked: true,
    workloads,
  };
}

const dash = (over: Partial<EksDashboard>): EksDashboard => ({ karpenter: null, ...over }) as unknown as EksDashboard;

// xamops-test-1's php-apache (the agent's HPADetail shape), pinned at its maximum of 5.
const PHP_AGENT = {
  name: 'php-apache', namespace: 'team-b', targetKind: 'Deployment', targetName: 'php-apache',
  minReplicas: 1, maxReplicas: 5, currentReplicas: 5, desiredReplicas: 5, cpuTargetPct: 50, currentCpuPct: 64,
  conditions: [
    { type: 'AbleToScale', status: 'True', reason: 'ReadyForNewScale' },
    { type: 'ScalingActive', status: 'True', reason: 'ValidMetricFound' },
    { type: 'ScalingLimited', status: 'True', reason: 'TooManyReplicas', message: 'the desired replica count is more than the maximum replica count' },
  ],
};

const PHP_E1 = wire('php-apache', 'team-b', {
  replicas: 5,
  hpa: { present: true, name: 'php-apache' },
  horizontal: { state: 'CHANGE', minReplicas: null, maxReplicas: 8, targetCpuUtilizationPct: null, reasons: ['AT_MAX_UNMET_DEMAND'] },
});

const hpaView = (workloads: VerticalWorkloadWire[]) => adaptHpaPage(body(workloads));

const agent = (h: Partial<AgentHpa>): AgentHpa => ({
  namespace: 'n', name: 'h', targetKind: 'Deployment', targetName: 'w', min: 1, max: 5, current: 3, desired: 3,
  cpuTargetPct: 50, cpuNowPct: 40, conditions: [], ...h,
});

describe('agentHpas: the XamOps agent HPAs, both field spellings', () => {
  it("reads the agent's cpuTargetPct/currentCpuPct and the older targetCPUUtilization/currentCPUUtilization", () => {
    const hpas = agentHpas(dash({
      k8sHpas: [PHP_AGENT, { name: 'worker-hpa', namespace: 'production', minReplicas: 1, maxReplicas: 5, currentReplicas: 2, desiredReplicas: 2, targetCPUUtilization: 60, currentCPUUtilization: 45 }],
    }))!;
    expect(hpas.map((h) => [h.name, h.cpuTargetPct, h.cpuNowPct, h.targetName])).toEqual([
      ['php-apache', 50, 64, 'php-apache'],
      ['worker-hpa', 60, 45, null],
    ]);
    expect(hpas[0].conditions[2]).toEqual({ type: 'ScalingLimited', status: 'True', reason: 'TooManyReplicas', message: 'the desired replica count is more than the maximum replica count' });
  });

  it('keeps an unreported reading null, and tells "no list" (null) from "none" ([])', () => {
    const [h] = agentHpas(dash({ k8sHpas: [{ name: 'x', namespace: 'n', minReplicas: 1, maxReplicas: 3, currentReplicas: 2 }] }))!;
    expect([h.cpuTargetPct, h.cpuNowPct, h.desired]).toEqual([null, null, null]);
    expect(agentHpas(dash({}))).toBeNull();
    expect(agentHpas(dash({ k8sHpas: [] }))).toEqual([]);
  });
});

describe('hpaFlags: first match (glossary §13.1)', () => {
  it('at its maximum, at its minimum, scaling normally, fixed size', () => {
    expect(hpaFlags(agent({ current: 5, max: 5 })).map((f) => f.label)).toEqual(['At its maximum']);
    expect(hpaFlags(agent({ current: 1, min: 1, cpuNowPct: 20 })).map((f) => f.label)).toEqual(['At its minimum']);
    // At the minimum with CPU above target, it is about to scale up: not flagged as at its minimum.
    expect(hpaFlags(agent({ current: 1, min: 1, cpuNowPct: 80 })).map((f) => f.label)).toEqual(['Scaling normally']);
    expect(hpaFlags(agent({ min: 3, max: 3, current: 3 })).map((f) => f.label)).toEqual(['Fixed size']);
  });

  it('a False ScalingActive comes first, with the condition in plain words; the rest become tags', () => {
    const flags = hpaFlags(agent({
      current: 5, max: 5,
      conditions: [{ type: 'ScalingActive', status: 'False', reason: 'FailedGetResourceMetric', message: 'missing request for cpu' }],
    }));
    expect(flags.map((f) => [f.key, f.tone])).toEqual([['not-scaling', 'bad'], ['at-max', 'warn']]);
    expect(flags[0].detail).toBe('Reading its metric: no · missing request for cpu');
  });

  it('flags nothing when the agent reported no replica numbers', () => {
    expect(hpaFlags(agent({ current: null, min: null, max: null }))).toEqual([]);
  });
});

describe('hpaConditionText (glossary §13.2)', () => {
  it.each([
    [{ type: 'AbleToScale', status: 'True', reason: 'ReadyForNewScale', message: null }, 'Can scale: yes'],
    [{ type: 'AbleToScale', status: 'True', reason: 'ScaleDownStabilized', message: null }, 'Can scale: yes · holding off a scale-down: recent readings asked for more'],
    [{ type: 'AbleToScale', status: 'False', reason: 'FailedGetScale', message: 'no scale' }, 'Can scale: no · no scale'],
    [{ type: 'ScalingActive', status: 'True', reason: 'ValidMetricFound', message: null }, 'Reading its metric: yes'],
    [{ type: 'ScalingLimited', status: 'True', reason: 'TooManyReplicas', message: 'x' }, 'Held at a limit: yes, the maximum'],
    [{ type: 'ScalingLimited', status: 'True', reason: 'TooFewReplicas', message: null }, 'Held at a limit: yes, the minimum'],
    [{ type: 'ScalingLimited', status: 'False', reason: 'DesiredWithinRange', message: null }, 'Held at a limit: no'],
    [{ type: 'Custom', status: 'True', reason: 'Odd', message: 'raw' }, 'Custom True: Odd · raw'],
  ])('%o reads %s', (c, text) => {
    expect(hpaConditionText(c)).toBe(text);
  });
});

describe('hpaRows: the agent joined with endpoint 1', () => {
  it('joins on namespace + target kind + target name, with the flag and the decision', () => {
    const rows = hpaRows(hpaView([PHP_E1]), dash({ k8sHpas: [PHP_AGENT] }));
    expect(rows).toHaveLength(1);
    const [r] = rows;
    expect([r.id, r.target, r.flag?.label, r.workload?.decision.maxReplicas]).toEqual(['team-b/php-apache', { kind: 'Deployment', name: 'php-apache' }, 'At its maximum', 8]);
    expect(r.live?.current).toBe(5);
  });

  it("falls back to the HPA name endpoint 1 reports when the agent doesn't name the target (the demo's shape)", () => {
    const e1 = wire('worker', 'production', { hpa: { present: true, name: 'worker-hpa' } });
    const rows = hpaRows(hpaView([e1]), dash({ k8sHpas: [{ name: 'worker-hpa', namespace: 'production', minReplicas: 1, maxReplicas: 5, currentReplicas: 2, targetCPUUtilization: 60, currentCPUUtilization: 45 }] }));
    expect(rows.map((r) => [r.id, r.workload?.ref.name, r.target])).toEqual([['production/worker-hpa', 'worker', { kind: 'Deployment', name: 'worker' }]]);
  });

  it('keeps an HPA only endpoint 1 knows (no live numbers) and one only the agent reports (no decision)', () => {
    const rows = hpaRows(hpaView([PHP_E1]), dash({ k8sHpas: [{ ...PHP_AGENT, name: 'other', targetName: 'other-app' }] }));
    expect(rows.map((r) => [r.id, r.live === null, r.workload === null])).toEqual([
      ['team-b/other', false, true],
      ['team-b/php-apache', true, false],
    ]);
    expect(rows[1].flag).toBeNull();
  });

  it('lists the D4 candidates (no HPA) apart, never as HPAs', () => {
    const candidate = wire('api', 'team-a', { horizontal: { state: 'CHANGE', minReplicas: 2, maxReplicas: 6, targetCpuUtilizationPct: 70, reasons: ['HPA_RECOMMENDED_NEW'] } });
    const view = hpaView([PHP_E1, candidate]);
    expect(hpaRows(view, null).map((r) => r.id)).toEqual(['team-b/php-apache']);
    expect(hpaCandidates(view).map((w) => w.id)).toEqual(['team-a/Deployment/api']);
  });

  it('counts the KPIs, with a dash where a source is missing', () => {
    const managed = wire('m', 'team-b', { hpa: { present: true, name: 'm' }, horizontal: { state: 'NO_CHANGE', minReplicas: null, maxReplicas: null, targetCpuUtilizationPct: null, reasons: ['VPA_MANAGED'] } });
    const rows = hpaRows(hpaView([PHP_E1, managed]), dash({ k8sHpas: [PHP_AGENT] }));
    expect(hpaKpis(rows, true, true)).toEqual({ total: 2, atMax: 1, atMin: 0, conflicts: 1 });
    expect(hpaKpis(rows, false, false)).toEqual({ total: 2, atMax: null, atMin: null, conflicts: null });
  });
});

describe('hpaWants: the HPA\'s own formula, only when computable', () => {
  it('ceil(current × now ÷ target), capped at its maximum, with the formula in words', () => {
    const w = hpaWants(agentHpas(dash({ k8sHpas: [PHP_AGENT] }))![0])!;
    expect([w.wants, w.capped]).toEqual([7, 5]);
    expect(w.formula).toContain('Its own formula: 5 × 64% ÷ 50%, rounded up.');
    expect(w.formula).toContain('Capped at 5.');
  });

  it("stays at current inside Kubernetes' 10% tolerance", () => {
    expect(hpaWants(agent({ current: 3, cpuNowPct: 52, cpuTargetPct: 50 }))!.wants).toBe(3);
  });

  it('is not shown without a CPU target, a reading or a replica count', () => {
    expect(hpaWants(agent({ cpuTargetPct: null }))).toBeNull();
    expect(hpaWants(agent({ cpuNowPct: null }))).toBeNull();
    expect(hpaWants(agent({ current: null }))).toBeNull();
  });
});

describe('hpaWorksChecks (glossary §13.3)', () => {
  it('CPU is left to the HPA; a VPA that changes pods fails; a disruption budget is named', () => {
    const managed = wire('php-apache', 'team-b', { hpa: { present: true, name: 'php-apache' }, horizontal: { state: 'NO_CHANGE', minReplicas: null, maxReplicas: null, targetCpuUtilizationPct: null, reasons: ['VPA_MANAGED', 'PDB_FLOOR_BINDS'] } });
    const [row] = hpaRows(hpaView([managed]), dash({ k8sHpas: [PHP_AGENT] }));
    expect(hpaWorksChecks(row).map((c) => [c.id, c.tone, c.label])).toEqual([
      ['cpu', 'good', 'Workload autoscaler leaves CPU alone'],
      ['vpa', 'bad', 'A Kubernetes VPA also changes these pods'],
      ['pdb', 'warn', 'A disruption budget needs more than the minimum'],
    ]);
  });

  it("says Not checked rather than passing a workload XamOps doesn't analyse", () => {
    const [row] = hpaRows(null, dash({ k8sHpas: [PHP_AGENT] }));
    expect(hpaWorksChecks(row).find((c) => c.id === 'vpa')!.label).toBe('Kubernetes VPA: not checked');
  });
});

describe('parseHpaParam', () => {
  it.each([['team-b/php-apache', { namespace: 'team-b', name: 'php-apache' }], ['a/b.c', { namespace: 'a', name: 'b.c' }]])('reads %s', (raw, ref) => {
    expect(parseHpaParam(raw)).toEqual(ref);
  });
  it.each(['team-b', 'team-b/', '/php', 'Team/x', 'a/b/c', 'a/-b', 'a/b c'])('rejects %s', (raw) => {
    expect(parseHpaParam(raw)).toBeNull();
  });
});

describe('vpaRows and vpaKpis', () => {
  const vpaTarget = (cpu: number | null, mem: number | null) => ({ cpuRequestM: cpu, memRequestMi: mem, source: 'vpa', observedAt: null });

  it('puts now, XamOps and the VPA side by side per pod, and compares CPU within 20%', () => {
    const same = wire('same', 'prod-sim', { containers: [{ name: 'app', qosClass: 'Burstable', current: res(100, 256), recommended: res(25, 256), reasons: [], upstreamVpa: vpaTarget(25, 250) }] });
    const near = wire('near', 'prod-sim', { containers: [{ name: 'app', qosClass: 'Burstable', current: res(100, 256), recommended: res(50, 256), reasons: [], upstreamVpa: vpaTarget(58, 250) }] });
    const far = wire('far', 'prod-sim', { containers: [{ name: 'app', qosClass: 'Burstable', current: res(100, 256), recommended: null, reasons: [], upstreamVpa: vpaTarget(10, 250) }] });
    const solo = wire('solo', 'prod-sim', { replicas: 1, skipReason: 'SINGLE_REPLICA', containers: [{ name: 'app', qosClass: 'Burstable', current: res(100, 256), recommended: null, reasons: [], upstreamVpa: vpaTarget(80, 250) }] });
    const b = body([same, near, far, solo]);
    const rows = vpaRows(adaptHpaPage(b), adaptEngineResponse(b));
    expect(rows.map((r) => [r.hpa.ref.name, r.cpu, r.compare?.label, r.agrees])).toEqual([
      ['same', { now: 100, xamops: 25, vpa: 25 }, 'CPU: same as XamOps', true],
      ['near', { now: 100, xamops: 50, vpa: 58 }, 'CPU: within 20% of XamOps', true],
      // No change suggested: XamOps's value is the request now.
      ['far', { now: 100, xamops: 100, vpa: 10 }, 'CPU: VPA suggests less than XamOps', false],
      // Skipped: XamOps has no value, so nothing to agree with.
      ['solo', { now: 100, xamops: null, vpa: 80 }, 'XamOps skips it: 1 replica', null],
    ]);
    expect(vpaKpis(rows)).toEqual({ vpas: 4, changingPods: 0, unreadable: 0, off: 4, conflicts: 0, agreement: { agree: 2, compared: 3 } });
  });

  it('a VPA without a target says so; only a workload with no pod running says why', () => {
    const managed = (replicas: number) => wire(`m${replicas}`, 'n', {
      replicas,
      containers: [{ name: 'app', qosClass: 'Burstable', current: res(100, 256), recommended: null, reasons: [], upstreamVpa: null }],
      horizontal: { state: 'NO_CHANGE', minReplicas: null, maxReplicas: null, targetCpuUtilizationPct: null, reasons: ['VPA_MANAGED'] },
    });
    const b = body([managed(0), managed(2)]);
    const rows = vpaRows(adaptHpaPage(b), adaptEngineResponse(b));
    expect(rows.map((r) => [r.noTarget, r.compare?.label])).toEqual([
      [true, 'No VPA suggestion: no running pod to measure'],
      [true, 'No VPA suggestion reported'],
    ]);
    expect(vpaKpis(rows)).toMatchObject({ changingPods: 2, conflicts: 2 });
  });
});

describe('Node autoscaler derivations', () => {
  const nodes = [
    { name: 'ip-1.ec2.internal', capacityType: 'on-demand', allocatableCpu: '1930m', allocatableMemory: '3200Mi', allocatablePods: '17', labels: { 'eks.amazonaws.com/nodegroup': 'ng-ondemand-1', 'node.kubernetes.io/instance-type': 't3.medium' }, conditions: [{ type: 'Ready', status: 'True' }] },
    { name: 'ip-2.ec2.internal', capacityType: 'on-demand', allocatableCpu: '1930m', allocatableMemory: '3200Mi', allocatablePods: '17', labels: { 'eks.amazonaws.com/nodegroup': 'ng-ondemand-1', 'node.kubernetes.io/instance-type': 't3.medium' }, conditions: [{ type: 'Ready', status: 'True' }] },
  ];
  const pods = [
    { name: 'a-1', namespace: 'team-a', phase: 'Running', nodeName: 'ip-1.ec2.internal', containers: [{ name: 'a', cpuRequest: '1000m', memoryRequest: '1Gi' }] },
    { name: 'b-1', namespace: 'team-a', phase: 'Running', nodeName: 'ip-2.ec2.internal', containers: [{ name: 'b', cpuRequest: '500m', memoryRequest: '512Mi' }] },
    { name: 'giant-1', namespace: 'team-a', phase: 'Pending', containers: [{ name: 'g', cpuRequest: '32', memoryRequest: '64Gi' }] },
  ];
  const D = dash({ k8sNodes: nodes, k8sPods: pods, k8sDeployments: [{ name: 'api', namespace: 'team-a' }] });

  it('who changes the node count: nothing here, the managed node group, and no autoscaler', () => {
    const lines = nodeChangers(D, 'xamops-test-1')!;
    expect(lines.map((l) => l.label)).toEqual([
      'Karpenter: not installed',
      'Cluster Autoscaler: not installed',
      'Node group ng-ondemand-1: EKS managed · t3.medium, 2 on-demand · 2 nodes now',
      'Nothing adds or removes nodes on xamops-test-1 today.',
    ]);
    expect(lines[2].detail).toBe('Without an autoscaler, EKS keeps it at the desired count.');
    expect(nodeChangers(null, 'c')).toBeNull();
  });

  it('names a Cluster Autoscaler Deployment with its tag', () => {
    const lines = nodeChangers(dash({ k8sNodes: nodes, k8sDeployments: [{ name: 'cluster-autoscaler', namespace: 'kube-system', images: ['registry.k8s.io/autoscaling/cluster-autoscaler:v1.30.0'] }] }), 'c')!;
    expect(lines.find((l) => l.id === 'cluster-autoscaler')!.label).toBe('Cluster Autoscaler: installed (kube-system/cluster-autoscaler, v1.30.0)');
    expect(lines.some((l) => l.id === 'none')).toBe(false);
  });

  it('KPIs and per-node bars from the agent: requested of allocatable, pods waiting, pods per node', () => {
    const k = nodeKpis(D);
    expect([k.nodes, k.ready, k.capacity, k.waiting]).toEqual([2, 2, { onDemand: 2, spot: 0, unknown: 0 }, 1]);
    expect(k.biggestWaiting).toMatchObject({ name: 'giant-1', cpuM: 32000 });
    expect(Math.round(k.cpu.requestedPct!)).toBe(39);
    expect(k.cpu.usedPct).toBeNull();
    expect(nodeBarRows(D)).toEqual([
      { node: 'ip-1.ec2.internal', requestedCpu: 1, allocatableCpu: 1.93, cpuRequestedPct: (1000 / 1930) * 100, pods: 1, allocatablePods: 17 },
      { node: 'ip-2.ec2.internal', requestedCpu: 0.5, allocatableCpu: 1.93, cpuRequestedPct: (500 / 1930) * 100, pods: 1, allocatablePods: 17 },
    ]);
  });

  const caps = (status: 'CONNECTED' | 'STALE' | 'NOT_INSTALLED' | 'UNKNOWN') => ({
    clusterName: 'c', automationAgent: { status, lastSeenAt: iso(40_000), version: '1.0.0' },
  }) as unknown as CapabilitiesCluster;

  it('the automation agent from capabilities; idle nodes only from its own report', () => {
    expect(automationAgentState('ready', caps('CONNECTED'))).toBe('installed');
    expect(automationAgentState('ready', caps('NOT_INSTALLED'))).toBe('not-installed');
    expect(automationAgentState('error', null)).toBe('unknown');
    expect(automationAgentState('ready', null)).toBe('unknown');
    expect(automationAgentLine('ready', caps('NOT_INSTALLED'), { kind: 'loading' }).label)
      .toBe('XamOps automation agent: not installed. It is the part of XamOps that empties and packs nodes.');
    expect(automationAgentLine('ready', caps('CONNECTED'), { kind: 'error', message: 'HTTP 500' }).detail)
      .toBe("XamOps couldn't read its node automation settings: HTTP 500");
    const fleet = { totalNodes: 2, onDemandNodes: 2, spotNodes: 0, idleNodes: [{ nodeName: 'ip-2', cpuUsagePct: 3, memUsagePct: 8 }], interruptedNodes: [], collectedAt: iso(55_000) };
    expect(idleNodeCount('installed', fleet)).toBe(1);
    expect(idleNodeCount('not-installed', fleet)).toBeNull();
    expect(idleNodeCount('installed', { ...fleet, collectedAt: null })).toBeNull();
  });

  it('every part of node autoscaling is "not available yet", with its reason', () => {
    const levers = nodeLevers({ agent: 'not-installed', provisioners: { karpenter: false, clusterAutoscaler: false }, kpis: nodeKpis(D), idle: null });
    expect(levers.map((l) => [l.id, l.reason])).toEqual([
      ['scale-up', "XamOps doesn't add nodes. Not built in XamOps yet."],
      ['remove-empty', "Needs the automation agent, which isn't installed on this cluster."],
      ['bin-packing', "Needs the automation agent, which isn't installed on this cluster."],
      ['rebalancing', 'No node provisioner detected.'],
      ['spot', 'Not built in XamOps yet.'],
    ]);
    expect(levers[0].verdict).toBe('1 pod is waiting. The largest, team-a/giant-1, asks for 32.00 vCPU and 64 GiB.');
    expect(levers[3].verdict).toBe('Not useful here: nothing would bring up replacement nodes (no Karpenter or Cluster Autoscaler).');
    expect(levers[4].verdict).toBe('All 2 nodes are on-demand.');
    const installed = nodeLevers({ agent: 'installed', provisioners: { karpenter: true, clusterAutoscaler: false }, kpis: nodeKpis(D), idle: 0, readiness: { 'evictor-live': 'Live consolidation is disabled.' } });
    expect(installed.find((l) => l.id === 'bin-packing')!.reason).toBe('Live consolidation is disabled.');
    expect(installed.find((l) => l.id === 'remove-empty')!.verdict).toBe('No node is idle.');
  });

  it('the last rebalance in words', () => {
    expect(rebalanceText({ status: 'idle', message: 'No rebalance has been run yet' })).toBe('No rebalance has been run yet');
    expect(rebalanceText({ status: 'failed', message: 'drain timed out' })).toBe('The last rebalance failed: drain timed out');
    expect(rebalanceText(null)).toBe('Not reported');
  });
});

describe('Activity: latest change per workload', () => {
  const at = (ms: number) => iso(ms);
  const b = body([
    wire('idle-batch-listener', 'dev-sim', { lastAction: { at: at(60 * 60_000), dryRun: false, outcome: 'APPLIED' } }),
    wire('overprovisioned-api', 'team-a', { lastAction: { at: at(30 * 60_000), dryRun: true, outcome: 'APPLIED' } }),
    wire('throttled-encoder', 'prod-sim', { lastAction: { at: at(2 * 60_000), dryRun: false, outcome: 'FAILED' } }),
    wire('mode', 'team-a', { lastAction: { at: at(3 * 24 * 3600_000), dryRun: false, outcome: 'POLICY_CHANGE' } }),
    wire('never', 'team-a'),
  ]);
  const rows = latestChangeRows(adaptEngineResponse(b).workloads);

  it('one row per workload with a recorded change, newest first, each with its result chip', () => {
    expect(rows.map((r) => [r.workload.ref.name, r.action, r.result.label, r.group, r.live])).toEqual([
      ['throttled-encoder', 'Resize', 'Failed', 'refused-failed', true],
      ['overprovisioned-api', 'Resize', 'Test run passed', 'test-run', false],
      ['idle-batch-listener', 'Resize', 'Applied', 'applied', true],
      ['mode', 'Mode change', 'Mode changed', 'mode-change', true],
    ]);
    expect(rows.every((r) => r.source === 'workload')).toBe(true);
  });

  it('counts applied, test runs, refused or failed and in place, and today by result (IST)', () => {
    expect(activityKpis(rows)).toEqual({ applied: 1, testRuns: 1, refusedOrFailed: 1, inPlace: 1 });
    expect(todayByResult(rows, NOW)).toEqual({ applied: 1, deferred: 0, refused: 0, failed: 1, testRuns: 1 });
  });

  it('the Kubernetes events stream keeps only scaling, resize and eviction reasons', () => {
    expect(['ResizeCompleted', 'ResizeDeferred', 'ScalingReplicaSet', 'SuccessfulRescale', 'Evicted'].every((r) => SCALING_EVENT_REASON.test(r))).toBe(true);
    expect(['BackOff', 'Pulled', 'FailedScheduling', 'NodeReady'].some((r) => SCALING_EVENT_REASON.test(r))).toBe(false);
  });
});
