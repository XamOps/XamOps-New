import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  DASH,
  adaptEngineResponse,
  codeText,
  formatCoverage,
  formatMib,
  formatMillis,
  parseCode,
  toApplyContainer,
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
      label: 'Cluster access not configured',
    });
    expect(parseCode('NO_METRICS_SOURCE')).toMatchObject({ kind: 'known', code: 'NO_METRICS_SOURCE' });
  });

  it('keeps an unrecognised code verbatim rather than dropping it', () => {
    const parsed = parseCode('SOME_FUTURE_REASON');
    expect(parsed).toEqual({ kind: 'unknown', raw: 'SOME_FUTURE_REASON' });
    expect(codeText(parsed!)).toBe('SOME_FUTURE_REASON');
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
    expect(result).toEqual({ kind: 'submitted', correlationId: 'c0ffee', state: 'ACCEPTED', dryRun: true });
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
    expect(result).toMatchObject({ kind: 'refused', message: 'Read-only.' });
  });
});
