import { describe, expect, it } from 'vitest';
import { adaptEngineResponse } from '../../../../../api/verticalRightsizerAdapter';
import { adaptHpaPage } from '../../../../../api/hpaAdapter';
import type {
  ApplyResult,
  VerticalEngineResponseWire,
  VerticalResourcesWire,
  VerticalWorkloadWire,
} from '../../../../../types/verticalRightsizer';
import type { EksDashboard } from '../../../../../types/eksTypes';
import type { CapabilitiesCluster } from '../../../../../types/k8sAutomation';
import {
  COOLDOWN_MS,
  DAY_MS,
  actionResult,
  addedPart,
  automationKpis,
  commonPolicy,
  formatCapacity,
  formatVcpu,
  freedPart,
  howItWorksChips,
  hpaFeatureStatus,
  latestCooldown,
  leftAloneRules,
  namespaceRollup,
  nodeAutoscalerStatus,
  parseWorkloadParam,
  podRequests,
  recentChanges,
  timelineLanes,
  vpaFeatureStatus,
  withParam,
  workloadAutoscalerStatus,
  workloadChange,
  workloadLifecycle,
  workloadStatusChip,
} from '../automationDerivations';

const NOW = Date.parse('2026-10-14T13:30:00Z');
const iso = (msAgo: number) => new Date(NOW - msAgo).toISOString();

const res = (cpuRequestM: number | null, memRequestMi: number | null, cpuLimitM: number | null = null, memLimitMi: number | null = null): VerticalResourcesWire =>
  ({ cpuRequestM, memRequestMi, cpuLimitM, memLimitMi });

function wire(name: string, over: Partial<VerticalWorkloadWire> = {}, namespace = 'team-a'): VerticalWorkloadWire {
  return {
    ref: { namespace, kind: 'Deployment', name },
    policy: { mode: 'RECOMMEND', headroomPct: 25, percentile: 95, window: '24h', applyMethod: 'IN_PLACE' },
    replicas: 2,
    hpa: { present: false, name: null },
    gitopsManaged: false,
    skipReason: null,
    findings: [],
    containers: [{ name: 'app', qosClass: 'Burstable', current: res(1000, 1024), recommended: res(25, 256), reasons: [], upstreamVpa: null }],
    lastAction: null,
    autoApplyEligible: false,
    provenance: { percentile: 95, windowHours: 24, windowStart: null, windowEnd: null, sampleCoverage: 0.94, usageSource: 'prometheus-proxy', policySource: 'DEFAULTS' },
    horizontal: null,
    ...over,
  };
}

function body(workloads: VerticalWorkloadWire[], over: Partial<VerticalEngineResponseWire> = {}, engine: Partial<VerticalEngineResponseWire['engine']> = {}): VerticalEngineResponseWire {
  return {
    engine: {
      enabled: true,
      ready: true,
      clusterAccess: 'CONFIGURED',
      executionPath: 'DIRECT',
      executionPathDetail: { direct: { available: true, reason: null }, agent: null },
      metricsSource: { reachable: true, name: 'prometheus-proxy' },
      lastCycle: { at: iso(5 * 60_000), evaluated: workloads.length, error: null },
      ...engine,
    },
    gateReasons: [],
    writeGatesChecked: true,
    workloads,
    ...over,
  };
}

const view = (workloads: VerticalWorkloadWire[], over?: Partial<VerticalEngineResponseWire>, engine?: Partial<VerticalEngineResponseWire['engine']>) =>
  adaptEngineResponse(body(workloads, over, engine));

const one = (w: VerticalWorkloadWire) => view([w]).workloads[0];

/** idle-batch-listener: 100m → 25m on 3 pods, applied 17 minutes ago (inside its wait). */
const IDLE = wire('idle-batch-listener', {
  replicas: 3,
  containers: [{ name: 'sleeper', qosClass: 'Burstable', current: res(100, 128, 200, 256), recommended: res(25, 128, 50, 256), reasons: [], upstreamVpa: null }],
  lastAction: { at: iso(17 * 60_000), dryRun: false, outcome: 'APPLIED' },
}, 'dev-sim');
/** throttled-encoder: needs more CPU (10m → 63m), 2 pods. */
const THROTTLED = wire('throttled-encoder', {
  findings: ['THROTTLED'],
  containers: [{ name: 'encoder', qosClass: 'Burstable', current: res(10, 64, 50, 128), recommended: res(63, 64, 200, 128), reasons: ['DEMAND_CENSORED_BY_LIMIT'], upstreamVpa: null }],
}, 'prod-sim');
const RIGHT = wire('steady', { containers: [{ name: 'app', qosClass: 'Burstable', current: res(100, 256), recommended: null, reasons: ['WITHIN_HYSTERESIS'], upstreamVpa: null }] });
const SINGLE = wire('solo', { replicas: 1, skipReason: 'SINGLE_REPLICA', containers: [{ name: 'app', qosClass: 'Burstable', current: res(100, 256), recommended: null, reasons: [], upstreamVpa: null }] });

describe('workloadChange: (current − suggested) × replicas, never 0-filled', () => {
  it('frees CPU and memory across every replica', () => {
    const change = workloadChange(one(wire('api')));
    expect(change.perPod).toEqual({ cpuM: 975, memMi: 768 });
    expect(change.total).toEqual({ cpuM: 1950, memMi: 1536 });
    expect(freedPart(change.total.cpuM)).toBe(1950);
    expect(addedPart(change.total.cpuM)).toBe(0);
  });

  it('adds for a workload that needs more', () => {
    const change = workloadChange(one(THROTTLED));
    expect(change.total.cpuM).toBe(-106);
    expect(addedPart(change.total.cpuM)).toBe(106);
    expect(freedPart(change.total.cpuM)).toBe(0);
  });

  it('is unknown, not 1×, when replicas are unknown, and unknown when the pods run different sizes', () => {
    expect(workloadChange(one(wire('a', { replicas: null })))).toMatchObject({ total: { cpuM: null, memMi: null }, totalUnknown: 'replicas-unknown' });
    const differ = wire('b', { containers: [{ name: 'app', qosClass: null, current: res(1000, 1024), recommended: res(25, 256), reasons: [], upstreamVpa: null, podsDisagree: true }] });
    expect(workloadChange(one(differ))).toMatchObject({ total: { cpuM: null }, totalUnknown: 'pods-differ' });
  });

  it('is unknown when one side of a request is unset, and zero when nothing would move', () => {
    const oneSide = wire('c', { containers: [{ name: 'app', qosClass: null, current: res(1000, null), recommended: res(25, 256), reasons: [], upstreamVpa: null }] });
    expect(workloadChange(one(oneSide)).total.memMi).toBeNull();
    expect(workloadChange(one(RIGHT)).total).toEqual({ cpuM: 0, memMi: 0 });
    const advisory = wire('d', { findings: ['REQUESTS_UNSET'], containers: [{ name: 'app', qosClass: 'BestEffort', current: res(null, null), recommended: res(60, 250), reasons: ['REQUESTS_UNSET'], upstreamVpa: null }] });
    expect(workloadChange(one(advisory)).total).toEqual({ cpuM: 0, memMi: 0 });
  });

  it('keeps a workload without a suggestion at its current request per pod', () => {
    expect(podRequests(one(RIGHT), 'cpu')).toEqual({ now: 100, suggested: 100 });
    expect(podRequests(one(wire('e')), 'memory')).toEqual({ now: 1024, suggested: 256 });
  });
});

describe('automationKpis', () => {
  const workloads = view([wire('api'), IDLE, THROTTLED, RIGHT, SINGLE, wire('unknown-replicas', { replicas: null })]).workloads;
  const k = automationKpis(workloads, NOW);

  it('counts over-provisioned and needs-more, and sums what they free and add', () => {
    expect(k.analysed).toBe(6);
    expect(k.overProvisioned).toBe(3);
    expect(k.needsMore).toBe(1);
    // api frees 1950m/1536Mi; idle-batch-listener frees 225m (memory unchanged); unknown replicas left out.
    expect(k.canSave).toEqual({ cpuM: 2175, memMi: 1536, counted: 2 });
    expect(k.needsMoreAdds).toEqual({ cpuM: 106, memMi: 0, counted: 1 });
    expect(k.notCounted).toBe(1);
  });

  it('counts live applies and the latest change per workload over 7 days', () => {
    expect(k.liveApplied).toBe(1);
    expect(k.changes7d).toBe(1);
    expect(k.changes7dByResult).toEqual([{ key: 'applied', tone: 'good', icon: 'mdi:check-circle', label: 'Applied', count: 1 }]);
  });

  it('leaves out a test run and a mode change from the live applies, and a change older than 7 days from the window', () => {
    const more = view([
      wire('dry', { lastAction: { at: iso(60_000), dryRun: true, outcome: 'APPLIED' } }),
      wire('mode', { lastAction: { at: iso(60_000), dryRun: false, outcome: 'POLICY_CHANGE' } }),
      wire('old', { lastAction: { at: iso(8 * DAY_MS), dryRun: false, outcome: 'PARTIALLY_APPLIED' } }),
    ]).workloads;
    const m = automationKpis(more, NOW);
    expect(m.liveApplied).toBe(1);
    expect(m.changes7d).toBe(2);
    expect(m.changes7dByResult.map((r) => r.label).sort()).toEqual(['Mode changed', 'Test run passed']);
  });
});

describe('recorded changes', () => {
  it('names a recorded outcome, and an unknown one as Other, never a success', () => {
    expect(actionResult('APPLIED', false)).toMatchObject({ key: 'applied', tone: 'good', label: 'Applied' });
    expect(actionResult('PARTIALLY_APPLIED', false)).toMatchObject({ key: 'partly-applied', tone: 'warn' });
    expect(actionResult('FAILED', false)).toMatchObject({ key: 'failed', tone: 'bad' });
    expect(actionResult('POLICY_CHANGE', false)).toMatchObject({ key: 'mode-change', tone: 'info', label: 'Mode changed' });
    expect(actionResult('SOMETHING_NEW', false)).toMatchObject({ key: 'other', tone: 'off', label: 'Other: SOMETHING_NEW' });
    expect(actionResult(null, null)).toBeNull();
  });

  it('times the wait from the newest recorded pod change, not a mode change', () => {
    const workloads = view([IDLE, wire('mode', { lastAction: { at: iso(60_000), dryRun: false, outcome: 'POLICY_CHANGE' } })]).workloads;
    const cd = latestCooldown(workloads)!;
    expect(cd.workload.ref.name).toBe('idle-batch-listener');
    expect(cd.endsAt).toBe(NOW - 17 * 60_000 + COOLDOWN_MS);
  });

  it('places each latest change in the 7-day lane chart, toned by its result', () => {
    const workloads = view([IDLE, wire('idle-batch-listener', { lastAction: { at: iso(DAY_MS), dryRun: false, outcome: 'FAILED' } }, 'other-ns')]).workloads;
    const lanes = timelineLanes(recentChanges(workloads, NOW), NOW);
    expect(lanes.map((l) => l.name)).toEqual(['dev-sim/idle-batch-listener', 'other-ns/idle-batch-listener']);
    expect(lanes[0].entries[0].tone).toBe('good');
    expect(lanes[0].entries[0].at).toBeCloseTo(1 - 17 / (7 * 24 * 60), 5);
    expect(lanes[1].entries[0].tone).toBe('bad');
    expect(lanes.every((l) => l.feature === 'workload')).toBe(true);
  });
});

describe('howItWorksChips (endpoint 1 only)', () => {
  it('reads Up to date, the suggestions, Waiting for you, the last confirmed change and the wait', () => {
    const chips = howItWorksChips(view([wire('api'), IDLE, THROTTLED]), NOW);
    expect(chips.measure).toMatchObject({ tone: 'good', label: 'Up to date' });
    expect(chips.decide).toMatchObject({ tone: 'info', label: '3 suggestions', detail: '2 smaller, 1 larger' });
    expect(chips.apply).toMatchObject({ tone: 'warn', label: 'Waiting for you' });
    expect(chips.verify).toMatchObject({ tone: 'good', detail: 'idle-batch-listener' });
    expect(chips.verify!.label).toMatch(/: confirmed$/);
    expect(chips.watch).toMatchObject({ tone: 'info' });
    expect(chips.watch!.label).toMatch(/^Until /);
  });

  it('says No usage data, Applying is off, and Nothing to watch from the gates', () => {
    const chips = howItWorksChips(
      view([RIGHT], { gateReasons: ['CONTROLLER_NOT_READY', 'NO_METRICS_SOURCE'] }, { ready: false, metricsSource: { reachable: false, name: null } }),
      NOW,
    );
    expect(chips.measure).toMatchObject({ tone: 'bad', label: 'No usage data' });
    expect(chips.decide).toMatchObject({ tone: 'good', label: 'No change needed' });
    expect(chips.apply).toMatchObject({ tone: 'off', label: 'Applying is off here' });
    expect(chips.verify).toBeUndefined();
    expect(chips.watch).toMatchObject({ tone: 'off', label: 'Nothing to watch' });
  });

  it('is View only for a caller write gate, and when the server never checked the caller', () => {
    const gated = howItWorksChips(view([wire('api')], { gateReasons: ['READ_ONLY_CONNECTION'] }), NOW);
    expect(gated.apply).toMatchObject({ tone: 'off', label: 'View only' });
    const unchecked = howItWorksChips(view([wire('api')], { writeGatesChecked: undefined }), NOW);
    expect(unchecked.apply).toMatchObject({ tone: 'off', label: 'View only', detail: "Couldn't check whether you can change automation." });
  });
});

describe('namespaceRollup: requested and used from the agent, suggested from endpoint 1', () => {
  const pod = (namespace: string, cpu: string, mem: string, phase = 'Running') => ({
    name: `${namespace}-${cpu}-${phase}`, namespace, phase, containers: [{ name: 'c', cpuRequest: cpu, memoryRequest: mem }],
  });
  const dashboard = {
    k8sPods: [pod('team-a', '1000m', '1Gi'), pod('team-a', '1000m', '1Gi'), pod('team-a', '200m', '64Mi'), pod('team-a', '500m', '1Gi', 'Succeeded'), pod('dev-sim', '100m', '128Mi')],
    namespaceUtilization: [{ namespace: 'team-a', cpuUsageMillicores: 40, memoryUsageMB: 300 }],
  } as unknown as EksDashboard;

  it('subtracts what the analysed workloads free; other pods stay at their request', () => {
    const { rows, agentPods, agentUsage } = namespaceRollup(view([wire('api')]).workloads, dashboard, 'cpu');
    expect(agentPods).toBe(true);
    expect(agentUsage).toBe(true);
    // Requested: two api pods + a 200m sidecar pod (the finished pod holds nothing); api frees 1950m.
    expect(rows).toEqual([{ namespace: 'team-a', requested: 2200, used: 40, suggested: 250 }]);
  });

  it('leaves a gap, not 0, when use isn’t reported or a suggestion’s amount is unknown', () => {
    const { rows } = namespaceRollup(view([IDLE, wire('x', { replicas: null }, 'dev-sim')]).workloads, dashboard, 'memory');
    expect(rows).toEqual([{ namespace: 'dev-sim', requested: 128, used: null, suggested: null }]);
  });

  it('has no requested side without the agent’s pods', () => {
    const { rows, agentPods } = namespaceRollup(view([wire('api')]).workloads, null, 'cpu');
    expect(agentPods).toBe(false);
    expect(rows[0]).toMatchObject({ requested: null, suggested: null });
  });
});

describe('workloadLifecycle (glossary §5)', () => {
  it('reads Not sized for a workload a rule leaves alone, with every stage to do', () => {
    const life = workloadLifecycle(one(SINGLE), { now: NOW });
    expect(life.label).toBe('Not sized: 1 replica');
    expect(life.states.every((s) => s.status === 'todo')).toBe(true);
  });

  it('reads Suggestion ready, then Watching until the wait ends after a recorded apply', () => {
    expect(workloadLifecycle(one(wire('api')), { now: NOW }).label).toBe('Suggestion ready');
    const life = workloadLifecycle(one(IDLE), { now: NOW });
    expect(life.label).toMatch(/^Watching until /);
    expect(life.states.map((s) => s.status)).toEqual(['done', 'done', 'done', 'done', 'current']);
    expect(workloadLifecycle(one(IDLE), { now: NOW + COOLDOWN_MS }).states[4].status).toBe('done');
  });

  it('marks Applying while the POST runs, and a deferral this session as the problem stage', () => {
    expect(workloadLifecycle(one(THROTTLED), { applying: true, now: NOW }).label).toBe('Applying');
    const deferred: ApplyResult = {
      kind: 'submitted', correlationId: 'c', state: 'ACCEPTED', dryRun: false,
      result: { method: 'DIRECT', terminal: 'DEFERRED', detail: null, restartFree: true, containers: null, pods: [], reverted: true },
    };
    const life = workloadLifecycle(one(THROTTLED), { reply: deferred, replyAt: NOW, now: NOW });
    expect(life.label).toBe('Deferred: no room on its node');
    expect(life.states[2]).toMatchObject({ stage: 'applying', status: 'problem', tone: 'warn' });
    // Put back and proved back: nothing changed, so no wait starts.
    expect(life.states[4].status).toBe('todo');
  });

  it('reads Measuring for a right-sized workload', () => {
    expect(workloadLifecycle(one(RIGHT), { now: NOW }).label).toBe('Measuring: no change needed');
  });

  // The reply's proof, not a default: skippedPods means never "N of N", restartFree false means "not proved".
  describe('an applied reply, read only for what it proves', () => {
    const pod = (name: string, restartsAfter = 0) => ({
      podName: name, uidBefore: 'u', uidAfter: 'u', samePod: true, ready: true, changed: true,
      containers: [{
        name: 'app', containerIdBefore: 'c1', containerIdAfter: 'c1', restartCountBefore: 0, restartCountAfter: restartsAfter,
        observedBefore: res(1000, 1024), observedAfter: res(25, 256),
      }],
    });
    const reply = (over: Partial<NonNullable<Extract<ApplyResult, { kind: 'submitted' }>['result']>>): ApplyResult => ({
      kind: 'submitted', correlationId: 'c', state: 'ACCEPTED', dryRun: false,
      result: { method: 'IN_PLACE', terminal: 'APPLIED', detail: null, restartFree: true, containers: null, pods: [pod('a-1'), pod('a-2')], ...over },
    });
    const details = (r: ApplyResult) => workloadLifecycle(one(wire('api')), { reply: r, replyAt: NOW, now: NOW }).details;

    it('every pod, proved restart-free', () => {
      expect(details(reply({}))).toMatchObject({ applying: '2 of 2 pods', applied: expect.stringMatching(/^Applied .+, no restart$/) });
    });

    it('a pod left out: every running pod (2 of 3), and no proof means no "no restart"', () => {
      const d = details(reply({ restartFree: false, skippedPods: [{ podName: 'a-3', phase: 'Pending', reason: 'NOT_RUNNING' }] }));
      expect(d.applying).toBe('Every running pod (2 of 3)');
      expect(d.applied).toMatch(/^Applied .+, no restart-free proof$/);
    });

    it('a restart count that rose reads restart detected', () => {
      expect(details(reply({ restartFree: false, pods: [pod('a-1', 1), pod('a-2')] })).applied).toMatch(/^Applied .+, restart detected$/);
    });
  });
});

describe('the ?workload= parameter (automation-v2 design §3.2)', () => {
  it.each([
    ['team-a/Deployment/overprovisioned-api', { namespace: 'team-a', kind: 'Deployment', name: 'overprovisioned-api' }],
    ['monitoring/StatefulSet/prometheus', { namespace: 'monitoring', kind: 'StatefulSet', name: 'prometheus' }],
    ['a.b/Deployment/c-1', { namespace: 'a.b', kind: 'Deployment', name: 'c-1' }],
  ])('reads %s', (raw, ref) => {
    expect(parseWorkloadParam(raw)).toEqual(ref);
  });

  it.each(['team-a/DaemonSet/x', 'Team-A/Deployment/x', 'team-a/Deployment/', 'team-a/Deployment/x/y', '-a/Deployment/x', 'team-a/deployment/x', '<script>', ''])(
    'rejects %s',
    (raw) => {
      expect(parseWorkloadParam(raw)).toBeNull();
    },
  );

  it('sets or removes one parameter, keeping the rest', () => {
    expect(withParam('/eks-details?name=c&tab=automation&sub=workload-autoscaler', 'workload', 'a/Deployment/b'))
      .toBe('/eks-details?name=c&tab=automation&sub=workload-autoscaler&workload=a%2FDeployment%2Fb');
    expect(withParam('/p?a=1&workload=x', 'workload', null)).toBe('/p?a=1');
  });
});

describe('policies and left alone', () => {
  const workloads = view([
    wire('api'),
    wire('auto', { policy: { mode: 'AUTO', headroomPct: 25, percentile: 95, window: '24h', applyMethod: 'IN_PLACE' } }),
    SINGLE,
    wire('locked', { skipReason: 'LOCKED', containers: [] }),
    wire('hpa', { hpa: { present: true, name: 'h' }, containers: [{ name: 'app', qosClass: null, current: res(100, 256), recommended: null, reasons: ['HPA_CPU_MANAGED'], upstreamVpa: null }] }),
    wire('dns', { skipReason: 'PROTECTED_NAMESPACE', containers: [] }, 'kube-system'),
  ]).workloads;

  it('finds the setting most workloads use', () => {
    const d = commonPolicy(workloads);
    expect(d.total).toBe(6);
    expect(d.mode).toEqual({ value: 'RECOMMEND', count: 5 });
    expect(d.headroomPct).toEqual({ value: 25, count: 6 });
    expect(d.policySource).toEqual({ value: 'DEFAULTS', count: 6 });
  });

  it('lists only the protections present, in the glossary order, with their workloads and wording', () => {
    const rules = leftAloneRules(workloads);
    expect(rules.map((r) => [r.label, r.workloads.map((w) => w.ref.name)])).toEqual([
      ['System namespaces', ['dns']],
      ['Single replica', ['solo']],
      ['Scaled by an HPA', ['hpa']],
      ['Paused', ['locked']],
    ]);
    expect(rules[1].sentence).toContain('single replica');
    expect(rules[3].sentence).toContain('xamops.io/lock: active');
  });

  it('lists VPA_MANAGED from the horizontal reasons, with skipReason null and no recommendation', () => {
    // The real engine shape for a mutating native VPA: the refusal rides on horizontal.reasons[],
    // not skipReason, and every container is frozen (recommended null).
    const vpa = wire('vpa-owned', {
      skipReason: null,
      horizontal: { state: 'CHANGE', minReplicas: null, maxReplicas: null, targetCpuUtilizationPct: null, reasons: ['VPA_MANAGED'] },
      containers: [{ name: 'app', qosClass: 'Burstable', current: res(100, 256), recommended: null, reasons: [], upstreamVpa: null }],
    });
    const rules = leftAloneRules(view([vpa]).workloads);
    const managed = rules.find((r) => r.id === 'VPA_MANAGED');
    expect(managed).toBeDefined();
    expect(managed!.label).toBe('Changed by a Kubernetes VPA');
    expect(managed!.workloads.map((w) => w.ref.name)).toEqual(['vpa-owned']);
  });
});

describe('feature cards', () => {
  it('reads the Workload autoscaler state from the engine and its gates', () => {
    expect(workloadAutoscalerStatus(view([wire('api')]), NOW)).toMatchObject({ tone: 'good', label: 'On: you apply suggestions' });
    expect(workloadAutoscalerStatus(view([wire('api')], { gateReasons: ['CONTROLLER_NOT_READY'] }, { ready: false }), NOW))
      .toMatchObject({ tone: 'warn', label: 'On: suggestions only' });
    expect(workloadAutoscalerStatus(view([], {}, { enabled: false }), NOW)).toMatchObject({ tone: 'off', label: 'Off in this environment' });
    expect(workloadAutoscalerStatus(view([wire('api')], { gateReasons: ['CLUSTER_UNREACHABLE'] }), NOW)).toMatchObject({ tone: 'bad', label: "Can't reach the cluster" });
  });

  it('counts the HPAs at their maximum from the agent, and says when it reported none', () => {
    const dashboard = { k8sHpas: [{ name: 'a', namespace: 'x', currentReplicas: 5, maxReplicas: 5 }, { name: 'b', namespace: 'x', currentReplicas: 1, maxReplicas: 4 }] } as unknown as EksDashboard;
    expect(hpaFeatureStatus(null, dashboard)).toMatchObject({ tone: 'warn', label: '1 HPA, at their maximum' });
    expect(hpaFeatureStatus(null, { k8sHpas: [] } as unknown as EksDashboard)).toMatchObject({ tone: 'off', label: 'No HPAs found' });
    expect(hpaFeatureStatus(null, {} as EksDashboard)).toMatchObject({ tone: 'off', label: 'Not reported by the XamOps agent' });
  });

  it('reads the VPA state from endpoint 1', () => {
    const b = body([wire('api', { containers: [{ name: 'app', qosClass: null, current: res(100, 256), recommended: null, reasons: [], upstreamVpa: { cpuRequestM: 50, memRequestMi: 128, source: 'vpa', observedAt: null } }] })]);
    expect(vpaFeatureStatus(adaptHpaPage(b), adaptEngineResponse(b))).toMatchObject({ tone: 'off', label: '1 VPA, all Off' });
    expect(vpaFeatureStatus(null, null)).toMatchObject({ label: 'Not checked' });
  });

  it('reads the node autoscaler from capabilities and the agent', () => {
    const cluster = { nodeAutomation: { enabled: false, mode: null, evictorAnalyseOnly: null, derivedState: 'NOT_INSTALLED' } } as unknown as CapabilitiesCluster;
    const status = nodeAutoscalerStatus('ready', cluster, { karpenter: null, k8sDeployments: [] } as unknown as EksDashboard);
    expect(status).toMatchObject({ tone: 'off', label: 'Not running on this cluster' });
    expect(status.lines).toContain("No node autoscaler in the XamOps agent's data");
    expect(nodeAutoscalerStatus('loading', null, null)).toMatchObject({ label: 'Checking' });
  });
});

describe('units (glossary v2 rule 6)', () => {
  it('writes totals in vCPU with two decimals and memory in Mi or GiB', () => {
    expect(formatVcpu(1950)).toBe('1.95 vCPU');
    expect(formatVcpu(0)).toBe('0.00 vCPU');
    expect(formatVcpu(2)).toBe('< 0.01 vCPU');
    expect(formatCapacity(225, 0)).toBe('0.23 vCPU');
    expect(formatCapacity(1950, 1536)).toBe('1.95 vCPU and 1.5 GiB');
  });

  it('keeps the workload status chip on the right tone', () => {
    expect(workloadStatusChip(one(wire('api')))).toMatchObject({ key: 'over-provisioned', tone: 'warn', label: 'Over-provisioned' });
    expect(workloadStatusChip(one(THROTTLED))).toMatchObject({ key: 'needs-more', tone: 'bad' });
    expect(workloadStatusChip(one(SINGLE))).toMatchObject({ key: 'skipped', tone: 'off', label: 'Skipped: only 1 replica' });
  });
});
