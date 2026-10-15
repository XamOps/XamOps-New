import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fetchAutomationRead } from '../automationReadApi';
import { fetchVerticalEngine, verticalReadUrl } from '../verticalRightsizerApi';
import { adaptEngineResponse } from '../verticalRightsizerAdapter';
import { adaptHpaPage } from '../hpaAdapter';
import type { VerticalEngineResponseWire } from '../../types/verticalRightsizer';

vi.mock('../../utils/apiClient', () => ({
  default: { fetch: vi.fn() },
}));

import apiClient from '../../utils/apiClient';

const mockedFetch = apiClient.fetch as unknown as ReturnType<typeof vi.fn>;

const answer = (status: number, body: unknown): Response =>
  ({
    status,
    ok: status >= 200 && status < 300,
    text: async () => (body === null ? '' : JSON.stringify(body)),
  }) as unknown as Response;

const BODY: VerticalEngineResponseWire = {
  engine: {
    enabled: true,
    ready: true,
    clusterAccess: 'CONFIGURED',
    executionPath: 'DIRECT',
    executionPathDetail: { direct: { available: true, reason: null }, agent: null },
    metricsSource: { reachable: true, name: 'prometheus-proxy' },
    lastCycle: { at: '2026-10-14T13:00:00Z', evaluated: 1, error: null },
  },
  gateReasons: [],
  writeGatesChecked: true,
  workloads: [
    {
      ref: { namespace: 'team-a', kind: 'Deployment', name: 'overprovisioned-api' },
      policy: { mode: 'RECOMMEND', headroomPct: 25, percentile: 95, window: '24h', applyMethod: 'IN_PLACE' },
      replicas: 2,
      hpa: { present: true, name: 'api-hpa' },
      gitopsManaged: false,
      skipReason: null,
      findings: [],
      containers: [
        {
          name: 'api',
          qosClass: 'Burstable',
          current: { cpuRequestM: 1000, memRequestMi: 1024, cpuLimitM: null, memLimitMi: null },
          recommended: { cpuRequestM: 25, memRequestMi: 250, cpuLimitM: null, memLimitMi: null },
          reasons: [],
          upstreamVpa: null,
        },
      ],
      lastAction: { at: '2026-10-14T13:11:00Z', dryRun: false, outcome: 'APPLIED' },
      autoApplyEligible: false,
      provenance: null,
      horizontal: { state: 'NO_CHANGE', minReplicas: 1, maxReplicas: 5, targetCpuUtilizationPct: 50, reasons: [] },
    },
  ],
};

const read = () => fetchAutomationRead('xamops-test-1', '102227938259', 'ap-south-1');

describe('fetchAutomationRead', () => {
  beforeEach(() => mockedFetch.mockReset());

  it('reads endpoint 1 once and adapts the same body into both views', async () => {
    mockedFetch.mockResolvedValue(answer(200, BODY));

    const result = await read();

    expect(mockedFetch).toHaveBeenCalledTimes(1);
    const [url, options] = mockedFetch.mock.calls[0];
    expect(url).toBe(verticalReadUrl('xamops-test-1', '102227938259', 'ap-south-1'));
    expect(options.method).toBe('GET');
    expect(result.kind).toBe('ok');
    if (result.kind !== 'ok') return;
    expect(result.view.vertical).toEqual(adaptEngineResponse(BODY));
    expect(result.view.hpa).toEqual(adaptHpaPage(BODY));
  });

  it.each([
    ['a lapsed session', () => answer(401, null)],
    ['an unregistered cluster with its message', () => answer(404, { code: 'K8S_CLUSTER_NOT_REGISTERED', message: 'Not here.' })],
    ['an unregistered cluster without a body', () => answer(404, null)],
    ['a typed refusal', () => answer(403, { code: 'K8S_CLUSTER_ACCESS_NOT_CONFIGURED', message: 'No access entry.' })],
    ['an untyped 403', () => answer(403, { message: 'Forbidden' })],
    ['a server error with its message', () => answer(500, { code: 'K8S_INTERNAL', message: 'Something went wrong.' })],
    ['a server error without a body', () => answer(502, null)],
    ['a 200 without an engine', () => answer(200, { workloads: [] })],
  ])('answers %s exactly as fetchVerticalEngine does', async (_label, response) => {
    mockedFetch.mockResolvedValueOnce(response()).mockResolvedValueOnce(response());

    const ours = await read();
    const theirs = await fetchVerticalEngine('xamops-test-1', '102227938259', 'ap-south-1');

    expect(ours.kind).not.toBe('ok');
    expect(ours).toEqual(theirs);
  });

  it('reports a request that could not be sent as an error, like fetchVerticalEngine', async () => {
    mockedFetch.mockRejectedValueOnce(new Error('network down')).mockRejectedValueOnce(new Error('network down'));

    const ours = await read();
    const theirs = await fetchVerticalEngine('xamops-test-1', '102227938259', 'ap-south-1');

    expect(ours).toEqual({ kind: 'error', message: 'network down' });
    expect(ours).toEqual(theirs);
  });
});
