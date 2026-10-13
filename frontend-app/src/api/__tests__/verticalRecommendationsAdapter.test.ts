import { describe, expect, it } from 'vitest';
import {
  CONTRACT_BLOCK_MESSAGE,
  VERTICAL_WRITES_CONFIRMED,
  formatProvenance,
  mapVerticalRefusal,
  recommendationLabel,
  toActionViews,
  toSubmissionView,
  toVerticalEngineView,
  verticalGates,
} from '../verticalRecommendationsAdapter';
import { K8sApiError } from '../k8sAutomationApi';
import { ACTION_IDS, type ActionId } from '../../types/k8sAutomation';
import type { ActionsPageWire, VerticalStatusWire, VerticalWorkloadWire } from '../../types/verticalRecommendations';

// ── Fixtures copied from the published contract ─────────────────────────────

/** Contract §2.1: the switch-off response (clusterAccess null, executionPath NONE). */
const DISABLED_CONTRACT: VerticalStatusWire = {
  engine: {
    enabled: false,
    ready: false,
    clusterAccess: null,
    executionPath: 'NONE',
    executionPathDetail: {
      direct: { available: false, reason: null },
      agent: { available: false, reason: 'AGENT_NOT_INSTALLED', lastSeenAt: null },
    },
    metricsSource: { reachable: false, name: null },
    lastCycle: { at: null, evaluated: null, error: null },
  },
  gateReasons: ['CONTROLLER_NOT_READY', 'NO_METRICS_SOURCE'],
  workloads: [],
};

/** The same switch-off state as ad5581ecd sends it: direct reason null, executionPath AGENT. */
const DISABLED_AD5581ECD: VerticalStatusWire = {
  ...DISABLED_CONTRACT,
  engine: {
    ...DISABLED_CONTRACT.engine,
    executionPath: 'AGENT',
    executionPathDetail: {
      direct: { available: false, reason: null },
      agent: { available: true, reason: null, lastSeenAt: '2026-10-10T09:00:00Z' },
    },
  },
  gateReasons: ['CONTROLLER_NOT_READY'],
};

/** Contract §2.3, verbatim. */
const WORKLOAD: VerticalWorkloadWire = {
  ref: { namespace: 'team-a', kind: 'Deployment', name: 'overprovisioned-api' },
  policy: { mode: 'RECOMMEND', headroomPct: 25, percentile: 95, window: '24h', applyMethod: 'IN_PLACE' },
  replicas: 2,
  hpa: { present: false, name: null },
  gitopsManaged: null,
  skipReason: null,
  findings: ['NODE_CAPACITY_CAPPED'],
  containers: [
    {
      name: 'api',
      qosClass: 'Guaranteed',
      current: { cpuRequestM: 1000, memRequestMi: 1024, cpuLimitM: 1000, memLimitMi: 1024 },
      recommended: { cpuRequestM: 25, memRequestMi: 250, cpuLimitM: 25, memLimitMi: 250 },
      reasons: ['FLOOR_CLAMPED'],
    },
  ],
  lastAction: null,
  autoApplyEligible: false,
  provenance: {
    percentile: 95, windowHours: 24,
    windowStart: '2026-10-09T02:00:00Z', windowEnd: '2026-10-10T02:00:00Z',
    sampleCoverage: 0.94, usageSource: 'none', policySource: 'DEFAULTS',
  },
  horizontal: { state: 'NOT_EVALUATED' },
};

const enabled = (over: Partial<VerticalStatusWire['engine']> = {}, gateReasons: string[] = ['CONTROLLER_NOT_READY'],
  workloads: VerticalWorkloadWire[] = []): VerticalStatusWire => ({
  engine: {
    enabled: true,
    ready: false,
    clusterAccess: 'NOT_CONFIGURED',
    executionPath: 'AGENT',
    executionPathDetail: {
      direct: { available: false, reason: 'CLUSTER_ACCESS_NOT_CONFIGURED' },
      agent: { available: true, reason: null, lastSeenAt: '2026-10-10T09:00:00Z' },
    },
    metricsSource: { reachable: true, name: 'in-cluster' },
    lastCycle: { at: '2026-10-10T08:00:00Z', evaluated: 9, error: null },
    ...over,
  },
  gateReasons,
  workloads,
});

const ACTIONS_PAGE: ActionsPageWire = {
  entries: [
    {
      at: '2026-10-10T04:15:22Z',
      correlationId: 'c0ffee',
      workloadRef: { namespace: 'team-a', kind: 'Deployment', name: 'overprovisioned-api' },
      containerChanges: [{
        name: 'api',
        from: { cpuRequestM: 1000, memRequestMi: 1024, cpuLimitM: 1000, memLimitMi: 1024 },
        to: { cpuRequestM: 25, memRequestMi: 250, cpuLimitM: 25, memLimitMi: 250 },
        status: 'APPLIED',
      }],
      method: 'IN_PLACE',
      dryRun: true,
      outcome: 'APPLIED',
      attempt: 1,
      triggeredBy: 'user@example.com',
    },
  ],
  nextBefore: null,
};

const VERTICAL_ACTIONS: ActionId[] = ['vertical.policy.recommend', 'vertical.policy.auto', 'vertical.method.persist', 'vertical.apply'];

// ── State ────────────────────────────────────────────────────────────────────

describe('toVerticalEngineView state', () => {
  it('the contract disabled response is V7, not V0 or V2', () => {
    expect(toVerticalEngineView(DISABLED_CONTRACT).state).toBe('V7');
  });

  it("ad5581ecd's disabled response (AGENT, null direct reason) is V7 too", () => {
    expect(toVerticalEngineView(DISABLED_AD5581ECD).state).toBe('V7');
  });

  it('evaluates V0, V2, V3, V4 in order', () => {
    expect(toVerticalEngineView(enabled({ executionPath: 'NONE' }, ['NO_METRICS_SOURCE'])).state).toBe('V0');
    expect(toVerticalEngineView(enabled({}, ['NO_METRICS_SOURCE', 'INSUFFICIENT_HISTORY'])).state).toBe('V2');
    expect(toVerticalEngineView(enabled({}, ['INSUFFICIENT_HISTORY'])).state).toBe('V3');
    expect(toVerticalEngineView(enabled({}, ['CONTROLLER_NOT_READY'])).state).toBe('V4');
  });

  it('accepts prefixed codes in gateReasons', () => {
    expect(toVerticalEngineView(enabled({}, ['K8S_NO_METRICS_SOURCE'])).gateReasons).toEqual(['NO_METRICS_SOURCE']);
  });
});

// ── Workloads ────────────────────────────────────────────────────────────────

describe('toVerticalEngineView workloads', () => {
  it('maps the contract workload with its enums known', () => {
    const view = toVerticalEngineView(enabled({}, ['CONTROLLER_NOT_READY'], [WORKLOAD]));
    const w = view.workloads[0];
    expect(w.policy.mode).toBe('RECOMMEND');
    expect(w.policy.percentile).toBe('95');
    expect(w.policy.window).toBe('24h');
    expect(w.policy.applyMethod).toBe('IN_PLACE');
    expect(w.findings).toEqual(['NODE_CAPACITY_CAPPED']);
    expect(w.containers[0].reasons).toEqual(['FLOOR_CLAMPED']);
    expect(w.containers[0].recommended).toEqual({ cpuRequestM: 25, memRequestMi: 250, cpuLimitM: 25, memLimitMi: 250 });
    expect(w.containers[0].qosChange).toBe(false);
    expect(w.containers[0].belowFloor).toBe(false);
    expect(w.invalidValue).toBe(false);
    expect(w.provenance?.sampleCoverage).toBe(0.94);
  });

  it('passes unknown enum values through as unknown', () => {
    const odd: VerticalWorkloadWire = {
      ...WORKLOAD,
      policy: { ...WORKLOAD.policy, mode: 'TURBO', window: '2w' },
      findings: ['NEW_FINDING'],
      skipReason: 'SOMETHING_NEW',
      containers: [{ ...WORKLOAD.containers[0], reasons: ['LIMIT_RAISED', 'LIMIT_MOVED', 'OOM_RISK'] }],
    };
    const view = toVerticalEngineView(enabled({ executionPath: 'TUNNEL' }, ['FUTURE_GATE'], [odd]));
    const w = view.workloads[0];
    expect(w.policy.mode).toEqual({ kind: 'unknown', raw: 'TURBO' });
    expect(w.policy.window).toEqual({ kind: 'unknown', raw: '2w' });
    expect(w.findings).toEqual([{ kind: 'unknown', raw: 'NEW_FINDING' }]);
    expect(w.skipReason).toEqual({ kind: 'unknown', raw: 'SOMETHING_NEW' });
    // NIT 1: LIMIT_RAISED is gone from the contract, LIMIT_MOVED and the container OOM_RISK exist.
    expect(w.containers[0].reasons).toEqual([{ kind: 'unknown', raw: 'LIMIT_RAISED' }, 'LIMIT_MOVED', 'OOM_RISK']);
    expect(view.executionPath).toEqual({ kind: 'unknown', raw: 'TUNNEL' });
    expect(view.gateReasons).toEqual([{ kind: 'unknown', raw: 'FUTURE_GATE' }]);
  });

  it('keeps missing numbers null and turns a 0 into null with the invalid-value flag', () => {
    const zero: VerticalWorkloadWire = {
      ...WORKLOAD,
      containers: [{
        name: 'api', qosClass: 'Burstable',
        current: { cpuRequestM: 0, memRequestMi: 512, cpuLimitM: null, memLimitMi: null },
        recommended: { cpuRequestM: 100, memRequestMi: 300 } as never,
        reasons: [],
      }],
    };
    const c = toVerticalEngineView(enabled({}, [], [zero])).workloads[0].containers[0];
    expect(c.current.cpuRequestM).toBeNull();
    expect(c.current.cpuLimitM).toBeNull();
    expect(c.recommended?.cpuLimitM).toBeNull();
    expect(c.invalidValue).toBe(true);
    expect(toVerticalEngineView(enabled({}, [], [zero])).workloads[0].invalidValue).toBe(true);
  });

  it('flags a Guaranteed container whose recommendation would change its QoS, and one below the floors', () => {
    const qos: VerticalWorkloadWire = {
      ...WORKLOAD,
      containers: [{ ...WORKLOAD.containers[0],
        recommended: { cpuRequestM: 100, memRequestMi: 300, cpuLimitM: 200, memLimitMi: 300 } }],
    };
    expect(toVerticalEngineView(enabled({}, [], [qos])).workloads[0].qosChange).toBe(true);
    const low: VerticalWorkloadWire = {
      ...WORKLOAD,
      containers: [{ ...WORKLOAD.containers[0],
        recommended: { cpuRequestM: 10, memRequestMi: 250, cpuLimitM: 10, memLimitMi: 250 } }],
    };
    expect(toVerticalEngineView(enabled({}, [], [low])).workloads[0].belowFloor).toBe(true);
  });

  it('reads recommended: null as "No change recommended" and a skipped workload as "No recommendation"', () => {
    const steady: VerticalWorkloadWire = {
      ...WORKLOAD, containers: [{ ...WORKLOAD.containers[0], recommended: null, reasons: ['WITHIN_HYSTERESIS'] }],
    };
    const skipped: VerticalWorkloadWire = { ...steady, skipReason: 'INSUFFICIENT_HISTORY' };
    const view = toVerticalEngineView(enabled({}, [], [steady, skipped]));
    expect(view.workloads[0].containers[0].recommended).toBeNull();
    expect(recommendationLabel(view.workloads[0], view.workloads[0].containers[0])).toBe('No change recommended');
    expect(recommendationLabel(view.workloads[1], view.workloads[1].containers[0])).toBe('No recommendation');
    expect(recommendationLabel(toVerticalEngineView(enabled({}, [], [WORKLOAD])).workloads[0],
      toVerticalEngineView(enabled({}, [], [WORKLOAD])).workloads[0].containers[0])).toBe('Recommended');
  });

  it('marks a cluster where every workload is POLICY_OFF', () => {
    const off: VerticalWorkloadWire = { ...WORKLOAD, skipReason: 'POLICY_OFF', containers: [] };
    expect(toVerticalEngineView(enabled({}, [], [off, off])).allWorkloadsOff).toBe(true);
    expect(toVerticalEngineView(enabled({}, [], [off, WORKLOAD])).allWorkloadsOff).toBe(false);
    expect(toVerticalEngineView(enabled({}, [], [])).allWorkloadsOff).toBe(false);
  });
});

// ── Provenance (NIT 2) ───────────────────────────────────────────────────────

describe('formatProvenance', () => {
  it('reads sampleCoverage as a fraction', () => {
    expect(formatProvenance(WORKLOAD.provenance)).toBe('p95 over 24h · 94% sample coverage');
  });

  it('names the usage source only when there is one', () => {
    expect(formatProvenance({ ...WORKLOAD.provenance!, usageSource: 'prometheus' }))
      .toBe('p95 over 24h · 94% sample coverage · usage from prometheus');
    expect(formatProvenance(null)).toBeNull();
  });
});

// ── Submissions and actions ──────────────────────────────────────────────────

describe('submissions and the actions feed', () => {
  it('wraps each 202 state', () => {
    for (const state of ['ACCEPTED', 'DUPLICATE', 'REFUSED']) {
      expect(toSubmissionView({ correlationId: 'c1', state, dryRun: true }).state).toBe(state);
    }
    expect(toSubmissionView({ correlationId: 'c1', state: 'QUEUED', dryRun: false }).state)
      .toEqual({ kind: 'unknown', raw: 'QUEUED' });
  });

  it('maps an actions page with dryRun on every entry', () => {
    const [entry] = toActionViews(ACTIONS_PAGE);
    expect(entry.outcome).toBe('APPLIED');
    expect(entry.method).toBe('IN_PLACE');
    expect(entry.dryRun).toBe(true);
    expect(toActionViews({ entries: [{ ...ACTIONS_PAGE.entries[0], outcome: 'ROLLED_BACK' }], nextBefore: null })[0].outcome)
      .toEqual({ kind: 'unknown', raw: 'ROLLED_BACK' });
  });
});

// ── Contract block (D16) ─────────────────────────────────────────────────────

describe('the contract block', () => {
  it('is pinned off until the engine write checks are confirmed', () => {
    expect(VERTICAL_WRITES_CONFIRMED).toBe(false);
  });

  it('blocks every vertical action with the contract message, whatever the engine says', () => {
    const ready = toVerticalEngineView(enabled({ ready: true }, []));
    for (const id of VERTICAL_ACTIONS) {
      expect(verticalGates({ view: ready }, id)[0]).toEqual({ reason: 'CONTROLLER_NOT_READY', message: CONTRACT_BLOCK_MESSAGE });
      expect(verticalGates({}, id)[0].message).toBe(CONTRACT_BLOCK_MESSAGE);
    }
  });

  it('adds nothing to non-vertical actions', () => {
    for (const id of ACTION_IDS.filter((a) => !a.startsWith('vertical.'))) {
      expect(verticalGates({ view: toVerticalEngineView(DISABLED_CONTRACT) }, id)).toEqual([]);
    }
  });
});

// ── Gate scoping (§6.2 step 2, §6.3) ─────────────────────────────────────────

const codesOf = (gates: ReturnType<typeof verticalGates>) => gates.slice(1).map((g) => g.reason);

describe('verticalGates scoping', () => {
  it('blocks Auto and apply when there is no execution path, with the direct reason on the second line', () => {
    const none = toVerticalEngineView(enabled({
      executionPath: 'NONE',
      executionPathDetail: {
        direct: { available: false, reason: 'CLUSTER_ACCESS_NOT_CONFIGURED' },
        agent: { available: false, reason: 'AGENT_NOT_INSTALLED', lastSeenAt: null },
      },
    }, []));
    for (const id of ['vertical.policy.auto', 'vertical.apply'] as const) {
      expect(verticalGates({ view: none }, id)[1]).toEqual({
        reason: 'AGENT_NOT_INSTALLED', detail: 'EXECUTION_PATH', directPath: 'CLUSTER_ACCESS_NOT_CONFIGURED',
      });
    }
    expect(codesOf(verticalGates({ view: none }, 'vertical.policy.recommend'))).toEqual([]);
    expect(codesOf(verticalGates({ view: none }, 'vertical.method.persist'))).toEqual([]);
  });

  it('never blocks a working agent path because the direct path is unreachable', () => {
    const agent = toVerticalEngineView(enabled({
      executionPathDetail: {
        direct: { available: false, reason: 'CLUSTER_UNREACHABLE' },
        agent: { available: true, reason: null, lastSeenAt: null },
      },
    }, ['CLUSTER_UNREACHABLE']));
    expect(codesOf(verticalGates({ view: agent }, 'vertical.apply'))).toEqual([]);
  });

  it('fails closed on a missing or unknown execution path', () => {
    const wire = enabled();
    const missing = toVerticalEngineView({ ...wire, engine: { ...wire.engine, executionPath: undefined as never } });
    expect(verticalGates({ view: missing }, 'vertical.apply')[1]).toEqual({ reason: 'UNKNOWN', raw: 'missing' });
  });

  it('scopes metrics and history to Auto and apply only', () => {
    const view = toVerticalEngineView(enabled({}, ['NO_METRICS_SOURCE', 'INSUFFICIENT_HISTORY']));
    expect(codesOf(verticalGates({ view }, 'vertical.policy.recommend'))).toEqual([]);
    expect(codesOf(verticalGates({ view }, 'vertical.method.persist'))).toEqual([]);
    expect(codesOf(verticalGates({ view }, 'vertical.policy.auto'))).toEqual(['NO_METRICS_SOURCE', 'INSUFFICIENT_HISTORY']);
    expect(codesOf(verticalGates({ view }, 'vertical.apply'))).toEqual(['NO_METRICS_SOURCE', 'INSUFFICIENT_HISTORY']);
  });

  it('sends the engine CONTROLLER_NOT_READY, the write gates and unknown codes to every vertical write', () => {
    const view = toVerticalEngineView(enabled({}, ['CONTROLLER_NOT_READY', 'READ_ONLY_CONNECTION', 'FUTURE_GATE']));
    for (const id of VERTICAL_ACTIONS) {
      const gates = verticalGates({ view }, id).slice(1);
      expect(gates).toEqual([
        { reason: 'CONTROLLER_NOT_READY' },
        { reason: 'READ_ONLY_CONNECTION' },
        { reason: 'UNKNOWN', raw: 'FUTURE_GATE' },
      ]);
    }
  });

  it('applies GITOPS_MANAGED to persisting changes only', () => {
    const view = toVerticalEngineView(enabled({}, [], [{ ...WORKLOAD, gitopsManaged: true }]));
    const workload = view.workloads[0];
    expect(codesOf(verticalGates({ view, workload }, 'vertical.method.persist'))).toEqual(['GITOPS_MANAGED']);
    expect(codesOf(verticalGates({ view, workload }, 'vertical.apply'))).toEqual([]);
    const persisting = { ...workload, policy: { ...workload.policy, applyMethod: 'IN_PLACE_AND_PERSIST' as const } };
    expect(codesOf(verticalGates({ view, workload: persisting }, 'vertical.apply'))).toEqual(['GITOPS_MANAGED']);
  });

  it('blocks every vertical write with UNKNOWN when the engine status failed', () => {
    for (const id of VERTICAL_ACTIONS) {
      expect(codesOf(verticalGates({ failed: true }, id))).toEqual(['UNKNOWN']);
    }
  });
});

// ── Refusals (NIT 1) ─────────────────────────────────────────────────────────

describe('mapVerticalRefusal', () => {
  const refusal = (code: string | null, status = 403) => new K8sApiError({ status, code, message: 'from the engine' });

  it('reads a direct-path refusal on Auto or apply as no execution path', () => {
    expect(mapVerticalRefusal(refusal('K8S_CLUSTER_UNREACHABLE'), 'vertical.apply')).toEqual({
      reason: 'AGENT_NOT_INSTALLED', detail: 'EXECUTION_PATH', directPath: 'CLUSTER_UNREACHABLE',
    });
    expect(mapVerticalRefusal(refusal('K8S_CLUSTER_ACCESS_NOT_CONFIGURED'), 'vertical.policy.auto')?.directPath)
      .toBe('CLUSTER_ACCESS_NOT_CONFIGURED');
  });

  it('keeps known gate codes with the engine message, drops error codes, and wraps unknown codes', () => {
    expect(mapVerticalRefusal(refusal('K8S_DEMO'), 'vertical.apply')).toEqual({ reason: 'DEMO', message: 'from the engine' });
    expect(mapVerticalRefusal(refusal('K8S_INVALID_POLICY', 400), 'vertical.policy.recommend')).toBeNull();
    expect(mapVerticalRefusal(refusal(null, 500), 'vertical.apply')).toBeNull();
    expect(mapVerticalRefusal(refusal('K8S_SOMETHING_NEW'), 'vertical.apply')).toEqual({
      reason: 'UNKNOWN', raw: 'K8S_SOMETHING_NEW', message: 'from the engine',
    });
  });
});
