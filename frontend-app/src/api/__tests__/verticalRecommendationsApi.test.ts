import { beforeEach, describe, expect, it, vi } from 'vitest';

const { apiFetch } = vi.hoisted(() => ({ apiFetch: vi.fn() }));
vi.mock('../../utils/apiClient', () => ({ apiFetch }));

import { K8sApiError } from '../k8sAutomationApi';
import {
  classifyVerticalFailure,
  getVerticalActions,
  getVerticalStatus,
  postVerticalApply,
  putVerticalPolicy,
} from '../verticalRecommendationsApi';

const target = { cluster: 'prod-eks', accountId: '111111111111', region: 'us-east-1' };
const ref = { namespace: 'team a', kind: 'Deployment', name: 'api' };

describe('classifyVerticalFailure', () => {
  const error = (status: number, code: string | null) => new K8sApiError({ status, code });

  it('decides by code, never by status alone', () => {
    expect(classifyVerticalFailure(error(404, null))).toBe('not-deployed');
    expect(classifyVerticalFailure(error(404, 'K8S_CLUSTER_NOT_REGISTERED'))).toBe('not-registered');
    expect(classifyVerticalFailure(error(403, null))).toBe('no-account-access');
    expect(classifyVerticalFailure(error(403, 'K8S_NO_ACCOUNT_ACCESS'))).toBe('no-account-access');
    expect(classifyVerticalFailure(error(403, 'K8S_READ_ONLY_CONNECTION'))).toBe('failed');
    expect(classifyVerticalFailure(error(500, 'K8S_INTERNAL'))).toBe('failed');
    expect(classifyVerticalFailure(error(500, null))).toBe('failed');
    expect(classifyVerticalFailure(error(400, 'K8S_INVALID_REQUEST'))).toBe('failed');
    expect(classifyVerticalFailure(error(0, null))).toBe('failed');
    expect(classifyVerticalFailure(new Error('boom'))).toBe('failed');
  });
});

describe('engine endpoints', () => {
  beforeEach(() => {
    apiFetch.mockReset();
    apiFetch.mockResolvedValue(new Response('{}', { status: 200 }));
  });

  it('carries accountId and region on every call', async () => {
    await getVerticalStatus(target);
    expect(apiFetch.mock.calls[0][0]).toBe('/api/eks/automation/vertical/prod-eks?accountId=111111111111&region=us-east-1');

    await getVerticalActions(target, { limit: 20, before: '2026-10-10T00:00:00Z' });
    expect(apiFetch.mock.calls[1][0]).toBe('/api/eks/automation/vertical/prod-eks/actions'
      + '?accountId=111111111111&region=us-east-1&limit=20&before=2026-10-10T00%3A00%3A00Z');

    await putVerticalPolicy(target, ref, { mode: 'RECOMMEND', headroomPct: 25, percentile: 95, window: '24h', applyMethod: 'IN_PLACE' });
    expect(apiFetch.mock.calls[2][0]).toBe('/api/eks/automation/vertical/prod-eks/workloads/team%20a/Deployment/api/policy'
      + '?accountId=111111111111&region=us-east-1');
    expect(apiFetch.mock.calls[2][1].method).toBe('PUT');

    await postVerticalApply(target, ref, { containers: [{ name: 'api', cpuRequestM: 25 }], applyMethod: 'IN_PLACE', dryRun: true });
    expect(apiFetch.mock.calls[3][1].method).toBe('POST');
    expect(JSON.parse(apiFetch.mock.calls[3][1].body).dryRun).toBe(true);
  });
});
