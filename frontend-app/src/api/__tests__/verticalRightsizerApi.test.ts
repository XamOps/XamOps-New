import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  applyVerticalRecommendation,
  verticalApplyUrl,
} from '../verticalRightsizerApi';
import type { ApplyRequest } from '../../types/verticalRightsizer';

vi.mock('../../utils/apiClient', () => ({
  default: { fetch: vi.fn() },
}));

import apiClient from '../../utils/apiClient';

const mockedFetch = apiClient.fetch as unknown as ReturnType<typeof vi.fn>;

const REF = { namespace: 'team-a', kind: 'Deployment', name: 'overprovisioned-api' };

const REQUEST: ApplyRequest = {
  containers: [{ name: 'api', cpuRequestM: 25, memRequestMi: 250, cpuLimitM: 25, memLimitMi: 250 }],
  applyMethod: 'IN_PLACE',
  dryRun: true,
};

const answer = (status: number, body: unknown): Response =>
  ({
    status,
    ok: status >= 200 && status < 300,
    text: async () => (body === null ? '' : JSON.stringify(body)),
  }) as unknown as Response;

const apply = () =>
  applyVerticalRecommendation('xamops-test-1', REF, '102227938259', 'ap-south-1', REQUEST);

describe('applyVerticalRecommendation', () => {
  beforeEach(() => mockedFetch.mockReset());

  it('posts the contract body to the contract path', async () => {
    mockedFetch.mockResolvedValue(answer(202, { correlationId: 'c0ffee', state: 'ACCEPTED', dryRun: true }));

    await apply();

    const [url, options] = mockedFetch.mock.calls[0];
    expect(url).toBe(
      '/api/eks/automation/vertical/xamops-test-1/workloads/team-a/Deployment/overprovisioned-api/apply' +
        '?accountId=102227938259&region=ap-south-1',
    );
    expect(url).toBe(verticalApplyUrl('xamops-test-1', REF, '102227938259', 'ap-south-1'));
    expect(options.method).toBe('POST');
    // dryRun is mandatory on the wire: the backend answers 400 when it is absent rather than
    // defaulting it to a live apply.
    expect(JSON.parse(options.body)).toEqual(REQUEST);
  });

  it('never invents a state the server did not send (review F6)', async () => {
    mockedFetch.mockResolvedValue(answer(202, { correlationId: 'c0ffee', dryRun: true }));

    const reply = await apply();

    expect(reply).toMatchObject({ kind: 'submitted', state: null });
  });

  it('carries the additive result block through, including the restart-free proof', async () => {
    mockedFetch.mockResolvedValue(
      answer(202, {
        correlationId: 'c0ffee',
        state: 'ACCEPTED',
        dryRun: false,
        result: {
          method: 'IN_PLACE',
          terminal: 'APPLIED',
          detail: null,
          restartFree: true,
          containers: [{ name: 'api', state: 'APPLIED', detail: null, requested: null, restartExpected: false }],
          pods: [
            {
              podName: 'api-1',
              uidBefore: 'uid-1',
              uidAfter: 'uid-1',
              samePod: true,
              ready: true,
              containers: [],
            },
          ],
        },
      }),
    );

    const result = await apply();

    expect(result.kind).toBe('submitted');
    if (result.kind !== 'submitted') return;
    expect(result.result?.terminal).toBe('APPLIED');
    expect(result.result?.restartFree).toBe(true);
    expect(result.result?.pods?.[0].samePod).toBe(true);
  });

  it('reports a submission with no result as a submission, with result null', async () => {
    mockedFetch.mockResolvedValue(answer(202, { correlationId: 'c0ffee', state: 'DUPLICATE', dryRun: false }));

    const result = await apply();

    expect(result).toEqual({
      kind: 'submitted',
      correlationId: 'c0ffee',
      state: 'DUPLICATE',
      dryRun: false,
      result: null,
    });
  });

  it('tells an unregistered cluster apart from an unmapped endpoint, because the remedies differ', async () => {
    mockedFetch.mockResolvedValue(
      answer(404, { code: 'K8S_CLUSTER_NOT_REGISTERED', message: 'Install the XamOps agent first.' }),
    );
    expect(await apply()).toEqual({
      kind: 'cluster-not-registered',
      message: 'Install the XamOps agent first.',
    });

    // An untyped 404 is the dispatcher: this environment runs a build with no handler mapped.
    mockedFetch.mockResolvedValue(answer(404, null));
    expect(await apply()).toEqual({ kind: 'writes-not-deployed' });
  });

  it('turns a 403 gate into a refusal carrying its code', async () => {
    mockedFetch.mockResolvedValue(
      answer(403, { code: 'K8S_READ_ONLY_CONNECTION', message: 'Connected read-only.' }),
    );

    const result = await apply();

    expect(result).toEqual({
      kind: 'refused',
      code: { kind: 'known', code: 'READ_ONLY_CONNECTION', label: 'Read-only connection' },
      message: 'Connected read-only.',
    });
  });

  it('turns a 400 K8S_INVALID_POLICY into a refusal, so the field message reaches the user', async () => {
    mockedFetch.mockResolvedValue(
      answer(400, { code: 'K8S_INVALID_POLICY', field: 'dryRun', message: 'dryRun is required.' }),
    );

    const result = await apply();

    expect(result.kind).toBe('refused');
    if (result.kind !== 'refused') return;
    expect(result.message).toBe('dryRun is required.');
  });
});
