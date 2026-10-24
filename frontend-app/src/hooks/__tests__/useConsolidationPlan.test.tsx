import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const { getConsolidationPlan } = vi.hoisted(() => ({ getConsolidationPlan: vi.fn() }));
vi.mock('../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/k8sAutomationApi')>();
  return { ...actual, getConsolidationPlan };
});

import { K8sApiError } from '../../api/k8sAutomationApi';
import type { ConsolidationPlanResponse } from '../../types/k8sAutomation';
import useConsolidationPlan from '../useConsolidationPlan';

const NO_PLAN: ConsolidationPlanResponse = { freshness: 'NO_PLAN', generatedAt: null, ageSeconds: null, plan: null, savings: null };

describe('useConsolidationPlan', () => {
  afterEach(() => vi.clearAllMocks());

  it('reads the plan once for the cluster and account, with an abort signal', async () => {
    getConsolidationPlan.mockResolvedValue(NO_PLAN);
    const { result } = renderHook(() => useConsolidationPlan('prod', '111', 'ap-south-1'));
    expect(result.current.plan.kind).toBe('loading');
    await waitFor(() => expect(result.current.plan).toEqual({ kind: 'ok', data: NO_PLAN }));
    expect(getConsolidationPlan).toHaveBeenCalledTimes(1);
    const [target, init] = getConsolidationPlan.mock.calls[0];
    expect(target).toEqual({ cluster: 'prod', accountId: '111', region: 'ap-south-1' });
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  it('settles to an error with the server message', async () => {
    getConsolidationPlan.mockRejectedValue(new K8sApiError({ status: 404, code: 'K8S_CLUSTER_NOT_REGISTERED', message: 'Not registered.' }));
    const { result } = renderHook(() => useConsolidationPlan('prod', '111', null));
    await waitFor(() => expect(result.current.plan).toEqual({ kind: 'error', message: 'Not registered.' }));
  });

  it('says so without a cluster or account, and reads nothing', async () => {
    const { result } = renderHook(() => useConsolidationPlan(null, '111', null));
    await waitFor(() => expect(result.current.plan).toEqual({ kind: 'error', message: 'No cluster or account was given.' }));
    expect(getConsolidationPlan).not.toHaveBeenCalled();
  });

  it('aborts on unmount and drops the late answer', async () => {
    let resolve: (v: ConsolidationPlanResponse) => void = () => {};
    getConsolidationPlan.mockReturnValue(new Promise<ConsolidationPlanResponse>((r) => { resolve = r; }));
    const { result, unmount } = renderHook(() => useConsolidationPlan('prod', '111', null));
    const signal: AbortSignal = getConsolidationPlan.mock.calls[0][1].signal;
    unmount();
    expect(signal.aborted).toBe(true);
    await act(async () => resolve(NO_PLAN));
    expect(result.current.plan.kind).toBe('loading');
  });

  it('reads again on retry', async () => {
    getConsolidationPlan.mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce(NO_PLAN);
    const { result } = renderHook(() => useConsolidationPlan('prod', '111', null));
    await waitFor(() => expect(result.current.plan).toEqual({ kind: 'error', message: 'boom' }));
    act(() => result.current.retry());
    await waitFor(() => expect(result.current.plan).toEqual({ kind: 'ok', data: NO_PLAN }));
    expect(getConsolidationPlan).toHaveBeenCalledTimes(2);
  });
});
