import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fetchRca, rcaUrl, adaptRca, type RcaResponseWire } from '../rcaApi';

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

const BODY: RcaResponseWire = {
  generatedAt: '2026-10-18T00:00:00Z',
  windowHours: 24,
  analyzed: { actionRows: 3, workloads: 1 },
  causes: [
    {
      kind: 'REPEATED_FAILED',
      title: 'Repeated apply failure',
      confidence: 0.55,
      workload: { namespace: 'prod-sim', kind: 'Deployment', name: 'throttled-encoder' },
      occurrences: 3,
      windowHours: 24,
      remediation: '3 FAILED applies in 24h on prod-sim/throttled-encoder.',
      evidence: [{ at: '2026-10-18T00:00:00Z', outcome: 'FAILED', detail: 'd', correlationId: 'c1' }],
    },
  ],
  dependencies: [{ kind: 'OOM_EVICTION', needs: 'Kubernetes Warning events persisted server-side' }],
};

const read = (w?: number) => fetchRca('xamops-test-1', '102227938259', 'ap-south-1', w);

describe('rcaUrl', () => {
  it('omits windowHours when not given, includes it when given', () => {
    expect(rcaUrl('c', '1', 'r')).toBe('/api/eks/automation/vertical/c/rca?accountId=1&region=r');
    expect(rcaUrl('c', '1', 'r', 48)).toContain('&windowHours=48');
  });
});

describe('adaptRca', () => {
  it('keeps measured numbers and maps causes', () => {
    const view = adaptRca(BODY);
    expect(view.windowHours).toBe(24);
    expect(view.analyzed).toEqual({ actionRows: 3, workloads: 1 });
    expect(view.causes).toHaveLength(1);
    expect(view.causes[0].confidence).toBe(0.55);
    expect(view.causes[0].workload.name).toBe('throttled-encoder');
    expect(view.dependencies[0].kind).toBe('OOM_EVICTION');
  });

  it('renders a non-finite or missing confidence as null, not zero', () => {
    const view = adaptRca({ ...BODY, causes: [{ ...BODY.causes![0], confidence: null }] });
    expect(view.causes[0].confidence).toBeNull();
  });

  it('defaults analyzed counts to zero and lists to empty when absent', () => {
    const view = adaptRca({});
    expect(view.analyzed).toEqual({ actionRows: 0, workloads: 0 });
    expect(view.causes).toEqual([]);
    expect(view.dependencies).toEqual([]);
  });
});

describe('fetchRca', () => {
  beforeEach(() => mockedFetch.mockReset());

  it('reads the rca sub-route once and adapts the body on 200', async () => {
    mockedFetch.mockResolvedValue(answer(200, BODY));
    const result = await read();
    expect(mockedFetch).toHaveBeenCalledTimes(1);
    const [url, options] = mockedFetch.mock.calls[0];
    expect(url).toBe(rcaUrl('xamops-test-1', '102227938259', 'ap-south-1'));
    expect(options.method).toBe('GET');
    expect(result.kind).toBe('ok');
    if (result.kind === 'ok') expect(result.view.causes).toHaveLength(1);
  });

  it('passes a windowHours hint through to the URL', async () => {
    mockedFetch.mockResolvedValue(answer(200, BODY));
    await read(9999);
    expect(mockedFetch.mock.calls[0][0]).toContain('&windowHours=9999');
  });

  it.each([
    ['a lapsed session', () => answer(401, null), { kind: 'unauthenticated' }],
    [
      'an unregistered cluster',
      () => answer(404, { code: 'K8S_CLUSTER_NOT_REGISTERED', message: 'Not here.' }),
      { kind: 'cluster-not-registered', message: 'Not here.' },
    ],
  ])('maps %s', async (_label, response, expected) => {
    mockedFetch.mockResolvedValueOnce(response());
    expect(await read()).toEqual(expected);
  });

  it('maps a typed 403 to a refusal with its code', async () => {
    mockedFetch.mockResolvedValueOnce(answer(403, { code: 'K8S_CLUSTER_ACCESS_NOT_CONFIGURED', message: 'No.' }));
    const result = await read();
    expect(result.kind).toBe('refused');
    if (result.kind === 'refused') expect(result.code.kind).toBe('known');
  });

  it('reports a 400 windowHours shape failure as an error with its message', async () => {
    mockedFetch.mockResolvedValueOnce(answer(400, { code: 'K8S_INVALID_REQUEST', message: 'bad windowHours', field: 'windowHours' }));
    const result = await read();
    expect(result.kind).toBe('error');
  });

  it('reports a request that could not be sent as an error', async () => {
    mockedFetch.mockRejectedValueOnce(new Error('network down'));
    expect(await read()).toEqual({ kind: 'error', message: 'network down' });
  });
});
