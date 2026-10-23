import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  DASH,
  adaptEngineResponse,
  codeText,
  formatCoverage,
  formatMib,
  formatMillis,
  formatMonthlyUsd,
  parseCode,
  toApplyContainer,
  workloadStatus,
} from '../verticalRightsizerAdapter';
import { applyVerticalRecommendation, fetchVerticalEngine, verticalApplyUrl } from '../verticalRightsizerApi';
import type { VerticalEngineResponseWire, VerticalWorkloadWire } from '../../types/verticalRightsizer';

vi.mock('../../utils/apiClient', () => ({
  default: { fetch: vi.fn() },
}));

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
  lastCycle: { at: '2026-10-11T10:00:00Z', evaluated: 1, error: null },
};

const workload = (overrides: Partial<VerticalWorkloadWire> = {}): VerticalWorkloadWire => ({
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
      upstreamVpa: { cpuRequestM: 30, memRequestMi: 250, source: 'vpa', observedAt: null },
    },
  ],
  lastAction: null,
  autoApplyEligible: false,
  provenance: {
    percentile: 95,
    windowHours: 24,
    windowStart: '2026-10-10T02:00:00Z',
    windowEnd: '2026-10-11T02:00:00Z',
    sampleCoverage: 0.94,
    usageSource: 'prometheus-proxy',
    policySource: 'DEFAULTS',
  },
  horizontal: { state: 'NOT_EVALUATED', minReplicas: null, maxReplicas: null, targetCpuUtilizationPct: null, reasons: [] },
  ...overrides,
});

describe('verticalRightsizerAdapter codes', () => {
  it('strips the refusal prefix and resolves the published label', () => {
    expect(parseCode('K8S_CLUSTER_ACCESS_NOT_CONFIGURED')).toEqual({
      kind: 'known',
      code: 'CLUSTER_ACCESS_NOT_CONFIGURED',
      label: 'No cluster access for XamOps',
    });
    expect(parseCode('NO_METRICS_SOURCE')).toMatchObject({ kind: 'known', code: 'NO_METRICS_SOURCE' });
  });

  it('keeps an unrecognised code verbatim rather than dropping it, shown as Other (glossary rule 3)', () => {
    const parsed = parseCode('SOME_FUTURE_REASON');
    expect(parsed).toEqual({ kind: 'unknown', raw: 'SOME_FUTURE_REASON' });
    expect(codeText(parsed!)).toBe('Other: SOME_FUTURE_REASON');
  });

  it('ignores null and blank codes', () => {
    expect(parseCode(null)).toBeNull();
    expect(parseCode('   ')).toBeNull();
  });
});

describe('verticalRightsizerAdapter resources', () => {
  it('treats null as unset and renders a dash, never 0', () => {
    const view = adaptEngineResponse({
      engine,
      gateReasons: [],
      workloads: [
        workload({
          containers: [
            {
              name: 'app',
              qosClass: 'BestEffort',
              current: { cpuRequestM: null, memRequestMi: null, cpuLimitM: null, memLimitMi: null },
              recommended: null,
              reasons: ['REQUESTS_UNSET'],
              upstreamVpa: null,
            },
          ],
        }),
      ],
    });
    const container = view.workloads[0].containers[0];
    expect(container.current.cpuRequestM).toEqual({ kind: 'unset' });
    expect(formatMillis(container.current.cpuRequestM)).toBe(DASH);
    expect(formatMib(container.current.memRequestMi)).toBe(DASH);
    expect(container.recommended).toBeNull();
    expect(container.upstreamVpa).toBeNull();
    expect(view.anomalies).toEqual([]);
  });

  it('surfaces a 0 as a payload defect and still refuses to render it as a number', () => {
    const view = adaptEngineResponse({
      engine,
      gateReasons: [],
      workloads: [
        workload({
          containers: [
            {
              name: 'app',
              qosClass: 'Burstable',
              current: { cpuRequestM: 0, memRequestMi: 1, cpuLimitM: null, memLimitMi: null },
              recommended: null,
              reasons: [],
              upstreamVpa: null,
            },
          ],
        }),
      ],
    });
    const container = view.workloads[0].containers[0];
    expect(container.current.cpuRequestM).toEqual({ kind: 'bug', raw: 0 });
    expect(formatMillis(container.current.cpuRequestM)).toBe(DASH);
    expect(view.anomalies).toHaveLength(1);
    expect(view.anomalies[0]).toContain('cpuRequestM');
  });

  it('carries provenance and the blocking reason for an ineligible workload', () => {
    const view = adaptEngineResponse({ engine, gateReasons: ['CONTROLLER_NOT_READY'], workloads: [workload()] });
    const w = view.workloads[0];
    expect(w.id).toBe('team-a/Deployment/overprovisioned-api');
    expect(w.autoApplyEligible).toBe(false);
    expect(w.applyBlockedBy).toMatchObject({ code: 'CONTROLLER_NOT_READY' });
    expect(formatCoverage(w.provenance!.sampleCoverage)).toBe('94%');
  });

  it('prefers the workload own skip reason over the cluster gate', () => {
    const view = adaptEngineResponse({
      engine,
      gateReasons: ['NO_METRICS_SOURCE'],
      workloads: [workload({ skipReason: 'INSUFFICIENT_HISTORY', containers: [] })],
    });
    expect(view.workloads[0].applyBlockedBy).toMatchObject({ code: 'INSUFFICIENT_HISTORY' });
    expect(view.workloads[0].containers).toEqual([]);
  });

  it('leaves applyBlockedBy null for an eligible workload', () => {
    const view = adaptEngineResponse({
      engine,
      gateReasons: [],
      workloads: [workload({ autoApplyEligible: true })],
    });
    expect(view.workloads[0].applyBlockedBy).toBeNull();
  });

  it('tolerates an engine-only payload with no workloads and no gates', () => {
    const view = adaptEngineResponse({ engine, gateReasons: null, workloads: null });
    expect(view.workloads).toEqual([]);
    expect(view.gateReasons).toEqual([]);
    expect(view.engine.metricsName).toBe('prometheus-proxy');
  });
});

describe('verticalRightsizerAdapter confidence (FEAT-001)', () => {
  it('carries the confidence block on a recommended container', () => {
    const view = adaptEngineResponse({
      engine,
      gateReasons: [],
      workloads: [
        workload({
          containers: [
            {
              name: 'api',
              qosClass: 'Guaranteed',
              current: { cpuRequestM: 1000, memRequestMi: 1024, cpuLimitM: 1000, memLimitMi: 1024 },
              recommended: { cpuRequestM: 25, memRequestMi: 250, cpuLimitM: 25, memLimitMi: 250 },
              reasons: ['FLOOR_CLAMPED'],
              upstreamVpa: null,
              confidence: { score: 0.82, band: 'HIGH', sampleCoverage: 0.96, windowHours: 24, belowFloor: false },
            },
          ],
        }),
      ],
    });
    expect(view.workloads[0].containers[0].confidence).toEqual({
      score: 0.82,
      band: 'HIGH',
      sampleCoverage: 0.96,
      windowHours: 24,
      belowFloor: false,
    });
  });

  it('is null when there is no recommendation, even if the wire carries a block', () => {
    const view = adaptEngineResponse({
      engine,
      gateReasons: [],
      workloads: [
        workload({
          containers: [
            {
              name: 'api',
              qosClass: 'Guaranteed',
              current: { cpuRequestM: 100, memRequestMi: 128, cpuLimitM: null, memLimitMi: null },
              recommended: null,
              reasons: ['WITHIN_HYSTERESIS'],
              upstreamVpa: null,
              confidence: { score: 0.9, band: 'HIGH', sampleCoverage: 1, windowHours: 24, belowFloor: false },
            },
          ],
        }),
      ],
    });
    expect(view.workloads[0].containers[0].confidence).toBeNull();
  });

  it('is null when the wire omits it (older build)', () => {
    const view = adaptEngineResponse({ engine, gateReasons: [], workloads: [workload()] });
    expect(view.workloads[0].containers[0].confidence).toBeNull();
  });

  it('drops an unrecognised band rather than guessing', () => {
    const view = adaptEngineResponse({
      engine,
      gateReasons: [],
      workloads: [
        workload({
          containers: [
            {
              name: 'api',
              qosClass: 'Guaranteed',
              current: { cpuRequestM: 1000, memRequestMi: 1024, cpuLimitM: 1000, memLimitMi: 1024 },
              recommended: { cpuRequestM: 25, memRequestMi: 250, cpuLimitM: 25, memLimitMi: 250 },
              reasons: ['FLOOR_CLAMPED'],
              upstreamVpa: null,
              confidence: { score: 0.5, band: 'MYSTERY', sampleCoverage: 0.6, windowHours: 24, belowFloor: false },
            },
          ],
        }),
      ],
    });
    expect(view.workloads[0].containers[0].confidence).toBeNull();
  });
});

describe('toApplyContainer', () => {
  it('sends only the fields that actually move', () => {
    const view = adaptEngineResponse({ engine, gateReasons: [], workloads: [workload()] });
    expect(toApplyContainer(view.workloads[0].containers[0])).toEqual({
      name: 'api',
      cpuRequestM: 25,
      memRequestMi: 250,
      cpuLimitM: 25,
      memLimitMi: 250,
    });
  });

  it('omits a resource the engine froze at its current value', () => {
    const view = adaptEngineResponse({
      engine,
      gateReasons: [],
      workloads: [
        workload({
          containers: [
            {
              name: 'php-apache',
              qosClass: 'Burstable',
              // HPA_CPU_MANAGED: the CPU request is republished unchanged, so it must not be patched.
              current: { cpuRequestM: 200, memRequestMi: 256, cpuLimitM: null, memLimitMi: null },
              recommended: { cpuRequestM: 200, memRequestMi: 250, cpuLimitM: null, memLimitMi: null },
              reasons: ['HPA_CPU_MANAGED'],
              upstreamVpa: null,
            },
          ],
        }),
      ],
    });
    expect(toApplyContainer(view.workloads[0].containers[0])).toEqual({ name: 'php-apache', memRequestMi: 250 });
  });

  it('returns null when nothing is recommended', () => {
    const view = adaptEngineResponse({
      engine,
      gateReasons: [],
      workloads: [
        workload({
          containers: [
            {
              name: 'worker',
              qosClass: 'Burstable',
              current: { cpuRequestM: 100, memRequestMi: 256, cpuLimitM: null, memLimitMi: null },
              recommended: null,
              reasons: ['WITHIN_HYSTERESIS'],
              upstreamVpa: null,
            },
          ],
        }),
      ],
    });
    expect(toApplyContainer(view.workloads[0].containers[0])).toBeNull();
  });
});

describe('verticalRightsizerAdapter cost (FEAT-002)', () => {
  it('formatMonthlyUsd renders "$X/mo" for a real number and a dash for null, never $0 for unknown', () => {
    expect(formatMonthlyUsd(42)).toBe('$42/mo');
    expect(formatMonthlyUsd(0.37)).toBe('$0.37/mo');
    expect(formatMonthlyUsd(0)).toBe('$0/mo'); // a true computed zero is a real "$0/mo"
    expect(formatMonthlyUsd(null)).toBe(DASH);
    expect(formatMonthlyUsd(undefined)).toBe(DASH);
    expect(formatMonthlyUsd(Number.NaN)).toBe(DASH);
  });

  it('adapts the per-workload and roll-up cost, carrying a true 0 and coercing capacityTypeInferred', () => {
    const view = adaptEngineResponse({
      engine,
      gateReasons: [],
      cost: {
        currency: 'USD', hoursPerMonth: 730, monthlySavings: 6.84, monthlySavingsUnavailableReason: null,
        clusterMonthlyCost: 47.52, pricedWorkloads: 2, unpricedWorkloads: 1, previousClusterMonthlyCost: 52.1,
      },
      workloads: [
        workload({
          cost: {
            monthlyCost: 12.34, monthlySavings: 0, reason: null, source: 'PRICE_LIST',
            capacityType: 'on-demand', capacityTypeInferred: true, note: 'AWS Price List on-demand rate',
          },
        }),
      ],
    });
    expect(view.cost).toEqual({
      currency: 'USD', hoursPerMonth: 730, monthlySavings: 6.84, monthlySavingsUnavailableReason: null,
      clusterMonthlyCost: 47.52, pricedWorkloads: 2, unpricedWorkloads: 1, previousClusterMonthlyCost: 52.1,
    });
    expect(view.workloads[0].cost).toEqual({
      monthlyCost: 12.34, monthlySavings: 0, reason: null, source: 'PRICE_LIST',
      capacityType: 'on-demand', capacityTypeInferred: true, note: 'AWS Price List on-demand rate',
    });
  });

  it('maps previousClusterMonthlyCost to null when absent or not a number (GAP 1: no prior source yet)', () => {
    // Absent on a response that predates the field, or carried as a non-number: both adapt to null,
    // so the Trend chip renders nothing rather than a fabricated trend.
    const absent = adaptEngineResponse({
      engine,
      gateReasons: [],
      cost: {
        currency: 'USD', hoursPerMonth: 730, monthlySavings: null, monthlySavingsUnavailableReason: null,
        clusterMonthlyCost: 47.52, pricedWorkloads: 1, unpricedWorkloads: 0,
      },
      workloads: [workload()],
    });
    expect(absent.cost?.previousClusterMonthlyCost).toBeNull();

    const nonNumber = adaptEngineResponse({
      engine,
      gateReasons: [],
      cost: {
        currency: 'USD', hoursPerMonth: 730, monthlySavings: null, monthlySavingsUnavailableReason: null,
        clusterMonthlyCost: 47.52, pricedWorkloads: 1, unpricedWorkloads: 0,
        previousClusterMonthlyCost: Number.NaN,
      },
      workloads: [workload()],
    });
    expect(nonNumber.cost?.previousClusterMonthlyCost).toBeNull();
  });

  it('carries null + reason when a workload is unpriced, and null cost when the response omits it', () => {
    const view = adaptEngineResponse({
      engine,
      gateReasons: [],
      workloads: [
        workload({ cost: { monthlyCost: null, monthlySavings: null, reason: 'NO_LIVE_PRICE', source: null, capacityType: null, capacityTypeInferred: false, note: null } }),
      ],
    });
    expect(view.workloads[0].cost).toMatchObject({ monthlyCost: null, monthlySavings: null, reason: 'NO_LIVE_PRICE', capacityTypeInferred: false });

    const older = adaptEngineResponse({ engine, gateReasons: [], workloads: [workload()] });
    expect(older.cost).toBeNull();
    expect(older.workloads[0].cost).toBeNull();
  });

  it('over-provisioned status leads with $X/mo when savings are real, keeping freed capacity secondary', () => {
    const view = adaptEngineResponse({
      engine,
      gateReasons: [],
      workloads: [
        workload({
          replicas: 1,
          containers: [{
            name: 'api', qosClass: 'Burstable',
            current: { cpuRequestM: 1000, memRequestMi: 1024, cpuLimitM: 1000, memLimitMi: 1024 },
            recommended: { cpuRequestM: 500, memRequestMi: 512, cpuLimitM: 1000, memLimitMi: 1024 },
            reasons: [], upstreamVpa: null,
          }],
          cost: { monthlyCost: 20, monthlySavings: 6.84, reason: null, source: 'PRICE_LIST', capacityType: 'on-demand', capacityTypeInferred: false, note: null },
        }),
      ],
    });
    const status = workloadStatus(view.workloads[0]);
    expect(status.status).toBe('Over-provisioned');
    expect(status.detail).toContain('$7/mo saved'); // formatMoney renders whole dollars above $1
    expect(status.detail).toMatch(/vCPU|memory/);
  });

  it('over-provisioned status falls to freed capacity + a reason when savings are unavailable', () => {
    const view = adaptEngineResponse({
      engine,
      gateReasons: [],
      workloads: [
        workload({
          replicas: 1,
          containers: [{
            name: 'api', qosClass: 'Burstable',
            current: { cpuRequestM: 1000, memRequestMi: 1024, cpuLimitM: 1000, memLimitMi: 1024 },
            recommended: { cpuRequestM: 500, memRequestMi: 512, cpuLimitM: 1000, memLimitMi: 1024 },
            reasons: [], upstreamVpa: null,
          }],
          cost: { monthlyCost: null, monthlySavings: null, reason: 'NO_LIVE_PRICE', source: null, capacityType: null, capacityTypeInferred: false, note: null },
        }),
      ],
    });
    const status = workloadStatus(view.workloads[0]);
    expect(status.status).toBe('Over-provisioned');
    expect(status.detail).toContain('cost unavailable (NO_LIVE_PRICE)');
    expect(status.detail).not.toContain('$');
  });
});

describe('verticalRightsizerApi', () => {
  beforeEach(() => {
    mockedFetch.mockReset();
  });

  it('adapts a 200 payload', async () => {
    mockedFetch.mockResolvedValue(
      new Response(JSON.stringify({ engine, gateReasons: [], workloads: [workload()] }), { status: 200 }),
    );
    const result = await fetchVerticalEngine('xamops-test-1', '102227938259', 'ap-south-1');
    expect(result.kind).toBe('ok');
  });

  it('maps a 404 on the read to cluster-not-registered with the server message', async () => {
    mockedFetch.mockResolvedValue(
      new Response(JSON.stringify({ code: 'K8S_CLUSTER_NOT_REGISTERED', message: 'Install the agent first.' }), {
        status: 404,
      }),
    );
    const result = await fetchVerticalEngine('xamops-test-1', '102227938259', 'ap-south-1');
    expect(result).toEqual({ kind: 'cluster-not-registered', message: 'Install the agent first.' });
  });

  it('maps a 403 gate on the read to a refusal carrying its code', async () => {
    mockedFetch.mockResolvedValue(
      new Response(JSON.stringify({ code: 'K8S_CLUSTER_ACCESS_NOT_CONFIGURED', message: 'No access entry.' }), {
        status: 403,
      }),
    );
    const result = await fetchVerticalEngine('xamops-test-1', '102227938259', 'ap-south-1');
    expect(result).toMatchObject({ kind: 'refused', message: 'No access entry.' });
  });

  it('treats a 404 from the apply endpoint as "not deployed yet", not a failure', async () => {
    mockedFetch.mockResolvedValue(new Response('', { status: 404 }));
    const result = await applyVerticalRecommendation(
      'xamops-test-1',
      { namespace: 'team-a', kind: 'Deployment', name: 'overprovisioned-api' },
      '102227938259',
      'ap-south-1',
      { containers: [{ name: 'api', cpuRequestM: 25 }], applyMethod: 'IN_PLACE', dryRun: true },
    );
    expect(result).toEqual({ kind: 'writes-not-deployed' });
  });

  it('sends the contract apply body to the contract apply path', async () => {
    mockedFetch.mockResolvedValue(
      new Response(JSON.stringify({ correlationId: 'c0ffee', state: 'ACCEPTED', dryRun: true }), { status: 202 }),
    );
    const ref = { namespace: 'team-a', kind: 'Deployment', name: 'overprovisioned-api' };
    const result = await applyVerticalRecommendation('xamops-test-1', ref, '102227938259', 'ap-south-1', {
      containers: [{ name: 'api', cpuRequestM: 25, memRequestMi: 250 }],
      applyMethod: 'IN_PLACE',
      dryRun: true,
    });

    expect(mockedFetch).toHaveBeenCalledTimes(1);
    const [url, options] = mockedFetch.mock.calls[0];
    expect(url).toBe(verticalApplyUrl('xamops-test-1', ref, '102227938259', 'ap-south-1'));
    expect(url).toBe(
      '/api/eks/automation/vertical/xamops-test-1/workloads/team-a/Deployment/overprovisioned-api/apply' +
        '?accountId=102227938259&region=ap-south-1',
    );
    expect(options.method).toBe('POST');
    expect(JSON.parse(options.body)).toEqual({
      containers: [{ name: 'api', cpuRequestM: 25, memRequestMi: 250 }],
      applyMethod: 'IN_PLACE',
      dryRun: true,
    });
    // result is null here because this 202 carried none: the agent path reports its outcome later.
    // The direct path answers with one, and verticalRightsizerApi.test.ts covers that shape.
    expect(result).toEqual({
      kind: 'submitted',
      correlationId: 'c0ffee',
      state: 'ACCEPTED',
      dryRun: true,
      result: null,
    });
  });

  it('reports a 403 on the apply as a refusal, never as a success', async () => {
    mockedFetch.mockResolvedValue(
      new Response(JSON.stringify({ code: 'K8S_READ_ONLY_CONNECTION', message: 'Read-only.' }), { status: 403 }),
    );
    const result = await applyVerticalRecommendation(
      'xamops-test-1',
      { namespace: 'team-a', kind: 'Deployment', name: 'overprovisioned-api' },
      '102227938259',
      'ap-south-1',
      { containers: [{ name: 'api', cpuRequestM: 25 }], applyMethod: 'IN_PLACE', dryRun: false },
    );
    expect(result).toMatchObject({ kind: 'refused', message: 'Read-only.', detail: null });
  });

  it('carries the permission rule a 403 names in detail', async () => {
    mockedFetch.mockResolvedValue(
      new Response(
        JSON.stringify({ code: 'K8S_NO_USER_PERMISSION', message: 'No automation key.', detail: 'TAB_GRANT' }),
        { status: 403 },
      ),
    );
    const result = await applyVerticalRecommendation(
      'xamops-test-1',
      { namespace: 'team-a', kind: 'Deployment', name: 'overprovisioned-api' },
      '102227938259',
      'ap-south-1',
      { containers: [{ name: 'api', cpuRequestM: 25 }], applyMethod: 'IN_PLACE', dryRun: true },
    );
    expect(result).toMatchObject({
      kind: 'refused',
      code: { kind: 'known', code: 'NO_USER_PERMISSION' },
      detail: 'TAB_GRANT',
    });
  });

  it('reads gateDetails and writeGatesChecked, and treats a missing flag as not checked', () => {
    const checked = adaptEngineResponse({
      engine,
      gateReasons: ['NO_USER_PERMISSION'],
      gateDetails: { NO_USER_PERMISSION: 'IMPERSONATION' },
      writeGatesChecked: true,
      workloads: [],
    });
    expect(checked.gateDetails).toEqual({ NO_USER_PERMISSION: 'IMPERSONATION' });
    expect(checked.writeGatesChecked).toBe(true);

    const older = adaptEngineResponse({ engine, gateReasons: [], workloads: [] });
    expect(older.gateDetails).toEqual({});
    expect(older.writeGatesChecked).toBe(false);
  });
});
