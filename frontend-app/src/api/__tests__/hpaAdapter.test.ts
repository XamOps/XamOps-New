import { describe, it, expect, vi } from 'vitest';
import { adaptHpaPage, hpaApplyGate, toHpaApplyRequest } from '../hpaAdapter';
import { applyHpaRecommendation, fetchHpaPage, hpaApplyUrl } from '../hpaApi';
import type { VerticalEngineResponseWire, VerticalWorkloadWire } from '../../types/verticalRightsizer';

vi.mock('../../utils/apiClient', () => ({ default: { fetch: vi.fn() } }));
// eslint-disable-next-line @typescript-eslint/no-var-requires
import apiClient from '../../utils/apiClient';
const mockedFetch = apiClient.fetch as unknown as ReturnType<typeof vi.fn>;

const engine: VerticalEngineResponseWire['engine'] = {
  enabled: true,
  ready: true,
  clusterAccess: 'CONFIGURED',
  executionPath: 'DIRECT',
  executionPathDetail: {
    direct: { available: true, reason: null },
    agent: { available: false, reason: 'AGENT_NOT_INSTALLED', lastSeenAt: null },
  },
  metricsSource: { reachable: true, name: 'prometheus-proxy' },
  lastCycle: { at: '2026-10-13T10:00:00Z', evaluated: 2, error: null },
};

/** The live php-apache workload: an HPA pinned 5/5, D1 wants max → 8, no VPA conflict. */
const phpApache = (overrides: Partial<VerticalWorkloadWire> = {}): VerticalWorkloadWire => ({
  ref: { namespace: 'team-b', kind: 'Deployment', name: 'php-apache' },
  policy: { mode: 'RECOMMEND', headroomPct: 25, percentile: 95, window: '24h', applyMethod: 'IN_PLACE' },
  replicas: 5,
  hpa: { present: true, name: 'php-apache' },
  gitopsManaged: null,
  skipReason: null,
  findings: [],
  containers: [
    {
      name: 'php-apache',
      qosClass: 'Burstable',
      current: { cpuRequestM: 200, memRequestMi: 256, cpuLimitM: null, memLimitMi: null },
      recommended: null,
      reasons: [],
      upstreamVpa: { cpuRequestM: 203, memRequestMi: 250, source: 'vpa', observedAt: null },
    },
  ],
  lastAction: null,
  autoApplyEligible: false,
  provenance: null,
  horizontal: {
    state: 'CHANGE',
    minReplicas: null,
    maxReplicas: 8,
    targetCpuUtilizationPct: null,
    reasons: ['AT_MAX_UNMET_DEMAND'],
    formula: 'ceil(5 × 64% ÷ 50%) = 7, capped to 8',
    autoApplyEligible: false,
  } as VerticalWorkloadWire['horizontal'],
  ...overrides,
});

const page = (workloads: VerticalWorkloadWire[], extra: Partial<VerticalEngineResponseWire> = {}) =>
  adaptHpaPage({ engine, gateReasons: [], workloads, writeGatesChecked: true, ...extra });

describe('adaptHpaPage decision block', () => {
  it('maps the HPA decision incl formula and the D1 raise-to-8', () => {
    const view = page([phpApache()]);
    const w = view.hpaWorkloads[0];
    expect(w.decision.state).toBe('CHANGE');
    expect(w.decision.maxReplicas).toBe(8);
    expect(w.decision.minReplicas).toBeNull();
    expect(w.decision.formula).toBe('ceil(5 × 64% ÷ 50%) = 7, capped to 8');
    expect(w.decision.reasons.map((r) => (r.kind === 'known' ? r.code : r.raw))).toEqual(['AT_MAX_UNMET_DEMAND']);
    expect(w.hpaPresent).toBe(true);
    expect(view.horizontalEvaluated).toBe(true);
  });

  it('forces autoApplyEligible false and keeps an unknown reason verbatim', () => {
    const view = page([
      phpApache({
        horizontal: {
          state: 'NO_CHANGE',
          minReplicas: null,
          maxReplicas: null,
          targetCpuUtilizationPct: null,
          reasons: ['SOME_FUTURE_REASON'],
        } as VerticalWorkloadWire['horizontal'],
      }),
    ]);
    const w = view.hpaWorkloads[0];
    expect(w.decision.autoApplyEligible).toBe(false);
    expect(w.decision.reasons[0]).toEqual({ kind: 'unknown', raw: 'SOME_FUTURE_REASON' });
  });

  it('treats NOT_EVALUATED (flag off) as no evaluation and keeps the page readable', () => {
    const view = page([
      phpApache({
        horizontal: {
          state: 'NOT_EVALUATED',
          minReplicas: null,
          maxReplicas: null,
          targetCpuUtilizationPct: null,
          reasons: [],
        },
      }),
    ]);
    expect(view.horizontalEvaluated).toBe(false);
    expect(view.hpaWorkloads).toHaveLength(1);
  });
});

describe('toHpaApplyRequest — null leaves alone', () => {
  it('sends only the fields the decision moves, dropping null bounds', () => {
    const view = page([phpApache()]);
    const body = toHpaApplyRequest(view.hpaWorkloads[0].decision, true);
    expect(body).toEqual({ maxReplicas: 8, dryRun: true });
    expect(body).not.toHaveProperty('minReplicas');
    expect(body).not.toHaveProperty('targetUtilizationPct');
  });

  it('returns null when the decision is not a CHANGE', () => {
    const view = page([
      phpApache({
        horizontal: { state: 'NO_CHANGE', minReplicas: null, maxReplicas: null, targetCpuUtilizationPct: null, reasons: ['WITHIN_REPLICA_HYSTERESIS'] },
      }),
    ]);
    expect(toHpaApplyRequest(view.hpaWorkloads[0].decision, false)).toBeNull();
  });
});

describe('VPA coexistence', () => {
  const offVpa = (name: string): VerticalWorkloadWire => ({
    ref: { namespace: 'prod-sim', kind: 'Deployment', name },
    policy: null,
    replicas: 2,
    hpa: { present: false, name: null },
    gitopsManaged: null,
    skipReason: null,
    findings: [],
    containers: [
      {
        name: 'app',
        qosClass: 'Burstable',
        current: { cpuRequestM: 100, memRequestMi: 256, cpuLimitM: null, memLimitMi: null },
        recommended: null,
        reasons: [],
        upstreamVpa: { cpuRequestM: 78, memRequestMi: 250, source: 'vpa', observedAt: null },
      },
    ],
    lastAction: null,
    autoApplyEligible: false,
    provenance: null,
    horizontal: { state: 'NO_CHANGE', minReplicas: null, maxReplicas: null, targetCpuUtilizationPct: null, reasons: [] },
  });

  it('reads an Off-mode VPA as coexistence off (no conflict, no VPA_MANAGED)', () => {
    const names = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i'];
    const view = page(names.map(offVpa));
    expect(view.vpaWorkloads).toHaveLength(9);
    expect(view.vpaWorkloads.every((w) => w.vpaCoexistence.kind === 'off')).toBe(true);
    expect(view.vpaWorkloads.every((w) => w.vpaCoexistence.kind !== 'managed')).toBe(true);
  });

  it('fires managed when a mutating VPA produced VPA_MANAGED', () => {
    const w = offVpa('mutating');
    w.horizontal = { state: 'NO_CHANGE', minReplicas: null, maxReplicas: null, targetCpuUtilizationPct: null, reasons: ['VPA_MANAGED'] };
    const view = page([w]);
    expect(view.vpaWorkloads[0].vpaCoexistence.kind).toBe('managed');
  });

  it('fires unreadable on the fail-safe VPA_NOT_READABLE', () => {
    const w = offVpa('x');
    w.containers![0].upstreamVpa = null;
    w.horizontal = { state: 'NO_CHANGE', minReplicas: null, maxReplicas: null, targetCpuUtilizationPct: null, reasons: ['VPA_NOT_READABLE'] };
    const view = page([w]);
    expect(view.vpaWorkloads[0].vpaCoexistence.kind).toBe('unreadable');
  });

  it('records a 0 in a native-VPA field as a payload defect and never renders it', () => {
    const w = offVpa('zero');
    w.containers![0].upstreamVpa = { cpuRequestM: 0, memRequestMi: 250, source: 'vpa', observedAt: null };
    const view = page([w]);
    expect(view.anomalies).toHaveLength(1);
    expect(view.anomalies[0]).toContain('cpuRequestM');
    expect(view.workloads[0].nativeVpa[0].cpuRequestM).toBeNull();
  });
});

describe('hpaApplyGate — gated/greyed with reason', () => {
  it('enables Apply for a real change when every gate passes', () => {
    const view = page([phpApache()]);
    const gate = hpaApplyGate(view, view.hpaWorkloads[0], true);
    expect(gate.enabled).toBe(true);
    expect(gate.reason).toBeNull();
    expect(gate.changes).toEqual({ maxReplicas: 8, dryRun: true });
  });

  it('greys Apply with the write-gates-not-checked reason when the server did not evaluate them', () => {
    const view = adaptHpaPage({ engine, gateReasons: [], workloads: [phpApache()] });
    const gate = hpaApplyGate(view, view.hpaWorkloads[0], true);
    expect(gate.enabled).toBe(false);
    expect(gate.reason).toBeTruthy();
  });

  it('greys Apply for a GitOps-managed HPA with a plain reason, no code visible', () => {
    const view = page([phpApache({ gitopsManaged: true })]);
    const gate = hpaApplyGate(view, view.hpaWorkloads[0], true);
    expect(gate.enabled).toBe(false);
    expect(gate.reason).toMatch(/Argo CD or Flux/);
    expect(gate.reason).not.toMatch(/GITOPS_MANAGED/);
  });

  it('greys Apply for a D4 recommend-new (never auto-created)', () => {
    const view = page([
      phpApache({
        hpa: { present: false, name: null },
        horizontal: { state: 'CHANGE', minReplicas: 2, maxReplicas: 6, targetCpuUtilizationPct: 80, reasons: ['HPA_RECOMMENDED_NEW'], formula: null, autoApplyEligible: false } as VerticalWorkloadWire['horizontal'],
      }),
    ]);
    const gate = hpaApplyGate(view, view.hpaWorkloads[0], true);
    expect(gate.enabled).toBe(false);
    expect(gate.reason).toMatch(/never creates an HPA/);
  });

  it('greys Apply when a safety rule refused (PDB floor), naming it in plain language', () => {
    const view = page([
      phpApache({
        horizontal: { state: 'NO_CHANGE', minReplicas: null, maxReplicas: null, targetCpuUtilizationPct: null, reasons: ['PDB_FLOOR_BINDS'] },
      }),
    ]);
    const gate = hpaApplyGate(view, view.hpaWorkloads[0], true);
    expect(gate.enabled).toBe(false);
    expect(gate.reason).toMatch(/PodDisruptionBudget/);
  });
});

describe('fetchHpaPage + applyHpaRecommendation', () => {
  it('builds the workload-scoped apply URL', () => {
    expect(hpaApplyUrl('c1', { namespace: 'team-b', kind: 'Deployment', name: 'php-apache' }, '123456789012', 'ap-south-1')).toBe(
      '/api/eks/automation/hpa/c1/team-b/php-apache/apply?accountId=123456789012&region=ap-south-1',
    );
  });

  it('adapts a 200 read into the HPA page view', async () => {
    mockedFetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      text: async () => JSON.stringify({ engine, gateReasons: [], workloads: [phpApache()], writeGatesChecked: true }),
    });
    const res = await fetchHpaPage('c1', '123456789012', 'ap-south-1');
    expect(res.kind).toBe('ok');
    if (res.kind === 'ok') expect(res.view.hpaWorkloads[0].decision.maxReplicas).toBe(8);
  });

  it('submits an apply and reports the applied bounds without inventing a success', async () => {
    mockedFetch.mockResolvedValueOnce({
      ok: true,
      status: 202,
      text: async () => JSON.stringify({ correlationId: 'cid', state: 'ACCEPTED', dryRun: false, applied: { minReplicas: null, maxReplicas: 8, targetUtilizationPct: null, dryRun: false } }),
    });
    const res = await applyHpaRecommendation('c1', { namespace: 'team-b', kind: 'Deployment', name: 'php-apache' }, '123456789012', 'ap-south-1', {
      maxReplicas: 8,
      dryRun: false,
    });
    expect(res.kind).toBe('submitted');
    if (res.kind === 'submitted') {
      expect(res.state).toBe('ACCEPTED');
      expect(res.applied?.maxReplicas).toBe(8);
    }
  });

  it('maps a 400 safety refusal to a typed refusal with the HpaReason detail', async () => {
    mockedFetch.mockResolvedValueOnce({
      ok: false,
      status: 400,
      text: async () => JSON.stringify({ code: 'K8S_INVALID_POLICY', message: 'A disruption budget needs more replicas.', field: 'minReplicas', detail: 'PDB_FLOOR_BINDS' }),
    });
    const res = await applyHpaRecommendation('c1', { namespace: 'team-b', kind: 'Deployment', name: 'php-apache' }, '123456789012', 'ap-south-1', {
      minReplicas: 1,
      dryRun: false,
    });
    expect(res.kind).toBe('refused');
    if (res.kind === 'refused') expect(res.detail).toBe('PDB_FLOOR_BINDS');
  });

  it('reads an untyped 404 as writes-not-deployed', async () => {
    mockedFetch.mockResolvedValueOnce({ ok: false, status: 404, text: async () => '' });
    const res = await applyHpaRecommendation('c1', { namespace: 'team-b', kind: 'Deployment', name: 'php-apache' }, '123456789012', 'ap-south-1', {
      maxReplicas: 8,
      dryRun: false,
    });
    expect(res.kind).toBe('writes-not-deployed');
  });
});
