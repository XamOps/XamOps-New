import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fetchSparklines, fetchUsage, sparklinesUrl, usageUrl } from '../automationUsageApi';
import { fetchHpaHistory, hpaHistoryUrl } from '../hpaApi';
import { adaptHpaHistory, adaptPoints, adaptSparklines, adaptUsage } from '../automationInsightsAdapter';
import type { HpaHistoryResponseWire, SparklinesResponseWire, UsageResponseWire } from '../../types/automationInsights';

vi.mock('../../utils/apiClient', () => ({
  default: { fetch: vi.fn() },
}));

import apiClient from '../../utils/apiClient';

const mockedFetch = apiClient.fetch as unknown as ReturnType<typeof vi.fn>;

const answer = (status: number, body: unknown): Response =>
  ({ status, ok: status >= 200 && status < 300, text: async () => (body === null ? '' : JSON.stringify(body)) }) as unknown as Response;

const SOURCE = { type: 'PROMETHEUS_PROXY', service: 'monitoring/prometheus-server:80' };

/** xamops-test-1's cluster CPU, three points: the middle one unmeasured. */
const CLUSTER: UsageResponseWire = {
  schemaVersion: 1, scope: 'cluster', resource: 'cpu', unit: 'cores', workloadRef: null, window: '72h', stepSeconds: 1800,
  start: '2026-10-14T10:00:00Z', end: '2026-10-14T11:00:00Z', source: SOURCE,
  series: [
    { key: 'used', kind: 'used', label: 'Used', points: [[1791972000, 0.6], [1791973800, null], [1791975600, 2.41]], query: 'sum(rate(container_cpu_usage_seconds_total[5m]))' },
    { key: 'request', kind: 'request', label: 'Requested', points: [[1791972000, 4.035], [1791973800, 5.985], [1791975600, 5.985]], query: 'sum(requests)' },
  ],
  namespaces: null,
  coverage: { firstSampleAt: '2026-10-14T10:00:00Z', covered: 0.6667 },
  unavailable: null,
};

describe('adaptPoints: a point nothing measured is a gap, never 0', () => {
  it('reads [epochSeconds, value] as epoch ms, keeps null as null and drops a point with no time', () => {
    expect(adaptPoints([[1791972000, 0.6], [1791973800, null], [1791975600, 'x'], ['later', 1], [1791977400], 'junk'])).toEqual([
      { t: 1791972000_000, v: 0.6 },
      { t: 1791973800_000, v: null },
      { t: 1791975600_000, v: null },
      { t: 1791977400_000, v: null },
    ]);
    expect(adaptPoints(null)).toEqual([]);
  });
});

describe('adaptUsage', () => {
  it('reads the series, the source and the coverage, nothing resampled', () => {
    const view = adaptUsage(CLUSTER);
    expect(view).toMatchObject({ scope: 'cluster', resource: 'cpu', unit: 'cores', window: '72h', stepSeconds: 1800, namespaces: null, unavailable: null });
    expect(view.source).toEqual({ type: 'PROMETHEUS_PROXY', service: 'monitoring/prometheus-server:80' });
    expect(view.start).toBe(Date.parse('2026-10-14T10:00:00Z'));
    expect(view.series.map((s) => [s.key, s.kind, s.label])).toEqual([['used', 'used', 'Used'], ['request', 'request', 'Requested']]);
    expect(view.series[0].points.map((p) => p.v)).toEqual([0.6, null, 2.41]);
    expect(view.coverage).toEqual({ firstSampleAt: Date.parse('2026-10-14T10:00:00Z'), covered: 0.6667 });
  });

  it('passes unavailable through with its code and words, with no series invented', () => {
    const view = adaptUsage({ ...CLUSTER, series: [], coverage: null, unavailable: { code: 'METRICS_TIMEOUT', message: 'Prometheus did not answer within 10 s.' } });
    expect(view.unavailable).toEqual({ code: 'METRICS_TIMEOUT', message: 'Prometheus did not answer within 10 s.' });
    expect(view.series).toEqual([]);
    expect(view.coverage).toBeNull();
  });

  it('reads every namespace with its average use and its request now, null when not measured', () => {
    const view = adaptUsage({
      ...CLUSTER,
      scope: 'namespace',
      series: [],
      namespaces: [
        { namespace: 'team-a', requestedNow: 2.035, usedAvg: 0.091, series: [{ key: 'used', kind: 'used', points: [[1791972000, 0.09]] }] },
        { namespace: 'idle', requestedNow: null, usedAvg: null, series: [] },
        { namespace: null, requestedNow: 1 },
      ],
    });
    expect(view.namespaces).toEqual([
      { namespace: 'team-a', requestedNow: 2.035, usedAvg: 0.091, series: [{ key: 'used', kind: 'used', label: null, points: [{ t: 1791972000_000, v: 0.09 }], query: null }] },
      { namespace: 'idle', requestedNow: null, usedAvg: null, series: [] },
    ]);
  });
});

describe('adaptSparklines', () => {
  it("keys each workload as endpoint 1 does and keeps a workload with no running pod as all-null, not dropped", () => {
    const wire: SparklinesResponseWire = {
      schemaVersion: 1, resource: 'cpu', unit: 'cores', window: '72h', stepSeconds: 3600, start: '2026-10-11T10:00:00Z', end: '2026-10-14T10:00:00Z', source: SOURCE,
      queries: { used: 'sum by (namespace, pod, container) (...)', request: 'max by (namespace, pod, container) (...)' },
      workloads: [
        { ref: { namespace: 'team-b', kind: 'Deployment', name: 'php-apache' }, used: [[1791972000, 0.142]], request: [[1791972000, 0.2]] },
        { ref: { namespace: 'dev-sim', kind: 'Deployment', name: 'idle-batch-listener' }, used: [[1791972000, null]], request: [[1791972000, null]] },
        { ref: { namespace: 'x', name: 'no-kind' }, used: [], request: [] },
      ],
      unavailable: null,
    };
    const view = adaptSparklines(wire);
    expect(view.workloads.map((w) => w.id)).toEqual(['team-b/Deployment/php-apache', 'dev-sim/Deployment/idle-batch-listener']);
    expect(view.workloads[1].used).toEqual([{ t: 1791972000_000, v: null }]);
    expect(view.queries).toEqual(wire.queries);
    expect(adaptSparklines({ ...wire, workloads: [], unavailable: { code: 'NO_METRICS_SOURCE', message: null } }).unavailable).toEqual({ code: 'NO_METRICS_SOURCE', message: null });
  });
});

describe('adaptHpaHistory', () => {
  const wire: HpaHistoryResponseWire = {
    schemaVersion: 1, namespace: 'team-b', name: 'php-apache', window: '72h', stepSeconds: 1800, start: '2026-10-11T10:00:00Z', end: '2026-10-14T10:00:00Z', source: SOURCE,
    series: [
      { key: 'currentReplicas', label: 'Replicas running', unit: 'replicas', points: [[1791972000, 5], [1791973800, null]], query: 'q1' },
      { key: 'cpuUtilization', label: 'CPU used', unit: 'percent', points: [[1791972000, 59]], query: 'q2' },
    ],
    atMax: {
      share: 0.9997, minutesAtMax: 4319, minutesMeasured: 4320,
      runs: [{ from: '2026-10-11T10:00:00Z', to: '2026-10-11T10:23:00Z' }, { from: '2026-10-11T10:24:00Z', to: null }, { from: null, to: null }],
      runsTruncated: false, since: '2026-10-11T10:24:00Z', stepSeconds: 60, query: 'q3',
    },
    changes: [{ at: '2026-10-11T10:23:00Z', from: 5, to: 1 }, { at: '2026-10-11T10:24:00Z', from: 1, to: 5 }, { at: 'bad', from: 1, to: 2 }],
    changesTruncated: false,
    firstSampleAt: '2026-10-11T10:00:00Z',
    unavailable: null,
  };

  it('reads the series, the spells at the maximum (one still running) and every replica change', () => {
    const view = adaptHpaHistory(wire);
    expect(view.series.map((s) => s.key)).toEqual(['currentReplicas', 'cpuUtilization']);
    expect(view.series[0].points.map((p) => p.v)).toEqual([5, null]);
    expect(view.atMax).toMatchObject({ share: 0.9997, minutesAtMax: 4319, minutesMeasured: 4320, stepSeconds: 60 });
    expect(view.atMax!.runs).toEqual([
      { from: Date.parse('2026-10-11T10:00:00Z'), to: Date.parse('2026-10-11T10:23:00Z') },
      { from: Date.parse('2026-10-11T10:24:00Z'), to: null },
    ]);
    expect(view.changes).toEqual([
      { at: Date.parse('2026-10-11T10:23:00Z'), from: 5, to: 1 },
      { at: Date.parse('2026-10-11T10:24:00Z'), from: 1, to: 5 },
    ]);
    expect(view.firstSampleAt).toBe(Date.parse('2026-10-11T10:00:00Z'));
  });

  it('keeps share null when nothing was measured, never 0%', () => {
    expect(adaptHpaHistory({ ...wire, atMax: { ...wire.atMax!, share: null, minutesMeasured: 0, minutesAtMax: 0, runs: [] } }).atMax!.share).toBeNull();
  });
});

describe('the Phase-2 read URLs', () => {
  it('asks for one scope, resource and window, with the workload only for scope=workload', () => {
    expect(usageUrl('xamops-test-1', '102227938259', 'ap-south-1', { scope: 'cluster', resource: 'cpu', window: '72h' })).toBe(
      '/api/eks/automation/usage/xamops-test-1?accountId=102227938259&region=ap-south-1&scope=cluster&resource=cpu&window=72h',
    );
    const workload = new URL(
      usageUrl('c', '1', 'r', { scope: 'workload', resource: 'memory', window: '24h', workload: { namespace: 'team-b', kind: 'Deployment', name: 'php-apache' } }),
      'http://test',
    );
    expect([...workload.searchParams.entries()]).toEqual([
      ['accountId', '1'], ['region', 'r'], ['scope', 'workload'], ['resource', 'memory'], ['window', '24h'],
      ['namespace', 'team-b'], ['kind', 'Deployment'], ['name', 'php-apache'],
    ]);
  });

  it('sends a custom window in UTC to the second, with its step', () => {
    const url = new URL(
      usageUrl('c', '1', 'r', {
        scope: 'workload', resource: 'cpu', window: 'custom', step: 60,
        workload: { namespace: 'dev-sim', kind: 'Deployment', name: 'idle-batch-listener' },
        from: Date.parse('2026-10-12T07:43:28.512Z'), to: Date.parse('2026-10-12T13:43:00Z'),
      }),
      'http://test',
    );
    expect(url.searchParams.get('from')).toBe('2026-10-12T07:43:28Z');
    expect(url.searchParams.get('to')).toBe('2026-10-12T13:43:00Z');
    expect(url.searchParams.get('step')).toBe('60');
  });

  it('asks for the sparklines and one HPA\'s history by its own name', () => {
    expect(sparklinesUrl('c', '1', 'r', 'cpu')).toBe('/api/eks/automation/usage/c/sparklines?accountId=1&region=r&resource=cpu&window=72h');
    expect(hpaHistoryUrl('xamops-test-1', { namespace: 'team-b', name: 'php-apache' }, '102227938259', 'ap-south-1', '72h')).toBe(
      '/api/eks/automation/hpa/xamops-test-1/team-b/php-apache/history?accountId=102227938259&region=ap-south-1&window=72h',
    );
  });
});

describe('fetchUsage, fetchSparklines and fetchHpaHistory: the status mapping', () => {
  beforeEach(() => mockedFetch.mockReset());

  it('reads with one GET, passing the abort signal, and adapts the answer', async () => {
    const signal = new AbortController().signal;
    mockedFetch.mockResolvedValueOnce(answer(200, CLUSTER));
    const result = await fetchUsage('c', '1', 'r', { scope: 'cluster', resource: 'cpu', window: '72h' }, signal);
    expect(mockedFetch.mock.calls[0][1]).toEqual({ method: 'GET', signal });
    expect(result.kind === 'ok' && result.view.series).toHaveLength(2);
  });

  it('reads a source failure in the cluster as an answer with unavailable, never as an empty chart', async () => {
    mockedFetch.mockResolvedValueOnce(answer(200, { ...CLUSTER, series: [], coverage: null, unavailable: { code: 'NO_METRICS_SOURCE', message: 'No Prometheus.' } }));
    const result = await fetchUsage('c', '1', 'r', { scope: 'cluster', resource: 'cpu', window: '72h' });
    expect(result.kind === 'ok' && result.view.unavailable).toEqual({ code: 'NO_METRICS_SOURCE', message: 'No Prometheus.' });
  });

  it('tells a backend without the feed (untyped 404) from an unknown cluster and an unknown workload (typed 404s)', async () => {
    mockedFetch.mockResolvedValueOnce(answer(404, { timestamp: '2026-10-14T10:00:00Z', status: 404, error: 'Not Found' }));
    expect(await fetchUsage('c', '1', 'r', { scope: 'cluster', resource: 'cpu', window: '72h' })).toEqual({ kind: 'not-deployed' });
    mockedFetch.mockResolvedValueOnce(answer(404, { code: 'K8S_CLUSTER_NOT_REGISTERED', message: "This cluster isn't registered with XamOps." }));
    expect(await fetchSparklines('c', '1', 'r', 'cpu')).toEqual({ kind: 'cluster-not-registered', message: "This cluster isn't registered with XamOps." });
    mockedFetch.mockResolvedValueOnce(answer(404, { code: 'K8S_INVALID_REQUEST', message: 'team-b/gone is not an HPA on this cluster.', field: 'name' }));
    expect(await fetchHpaHistory('c', { namespace: 'team-b', name: 'gone' }, '1', 'r', '72h')).toMatchObject({
      kind: 'refused', message: 'team-b/gone is not an HPA on this cluster.', field: 'name',
    });
  });

  it('reads a 401 as a lapsed session, typed 403 and 400 as refusals, and anything else as an error', async () => {
    const q = { scope: 'cluster', resource: 'cpu', window: '72h' } as const;
    mockedFetch.mockResolvedValueOnce(answer(401, null));
    expect(await fetchUsage('c', '1', 'r', q)).toEqual({ kind: 'unauthenticated' });
    mockedFetch.mockResolvedValueOnce(answer(403, { code: 'K8S_NO_ACCOUNT_ACCESS', message: "You don't have access to this account." }));
    expect(await fetchUsage('c', '1', 'r', q)).toMatchObject({ kind: 'refused', code: { kind: 'known', code: 'NO_ACCOUNT_ACCESS' } });
    mockedFetch.mockResolvedValueOnce(answer(400, { code: 'K8S_INVALID_REQUEST', message: 'step must be 60, 300, 1800 or 3600.', field: 'step' }));
    expect(await fetchUsage('c', '1', 'r', q)).toMatchObject({ kind: 'refused', field: 'step' });
    mockedFetch.mockResolvedValueOnce(answer(502, null));
    expect(await fetchUsage('c', '1', 'r', q)).toEqual({ kind: 'error', message: 'The usage feed returned HTTP 502.' });
    mockedFetch.mockResolvedValueOnce(answer(200, { engine: { enabled: true }, workloads: [] }));
    expect(await fetchUsage('c', '1', 'r', q)).toEqual({ kind: 'error', message: "The usage feed answered in a shape XamOps can't read." });
    mockedFetch.mockRejectedValueOnce(new Error('Network down'));
    expect(await fetchHpaHistory('c', { namespace: 'n', name: 'h' }, '1', 'r', '72h')).toEqual({ kind: 'error', message: 'Network down' });
  });
});
