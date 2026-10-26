import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const { getConsolidationRuns } = vi.hoisted(() => ({ getConsolidationRuns: vi.fn() }));
vi.mock('../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/k8sAutomationApi')>();
  return { ...actual, getConsolidationRuns };
});

import { K8sApiError } from '../../api/k8sAutomationApi';
import type { ConsolidationRunResponse } from '../../types/k8sAutomation';
import useConsolidationRuns from '../useConsolidationRuns';

const NO_RUNS: ConsolidationRunResponse = { status: 'NO_RUNS', runs: [], realizedSavings: null };

describe('useConsolidationRuns', () => {
  afterEach(() => vi.clearAllMocks());

  it('reads the run history once for the cluster and account, with an abort signal', async () => {
    getConsolidationRuns.mockResolvedValue(NO_RUNS);
    const { result } = renderHook(() => useConsolidationRuns('prod', '111', 'ap-south-1'));
    expect(result.current.runs.kind).toBe('loading');
    await waitFor(() => expect(result.current.runs).toEqual({ kind: 'ok', data: NO_RUNS }));
    expect(getConsolidationRuns).toHaveBeenCalledTimes(1);
    const [target, init] = getConsolidationRuns.mock.calls[0];
    expect(target).toEqual({ cluster: 'prod', accountId: '111', region: 'ap-south-1' });
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  it('settles to an error with the server message', async () => {
    getConsolidationRuns.mockRejectedValue(new K8sApiError({ status: 404, code: 'K8S_CLUSTER_NOT_REGISTERED', message: 'Not registered.' }));
    const { result } = renderHook(() => useConsolidationRuns('prod', '111', null));
    await waitFor(() => expect(result.current.runs).toEqual({ kind: 'error', message: 'Not registered.' }));
  });

  it('says so without a cluster or account, and reads nothing', async () => {
    const { result } = renderHook(() => useConsolidationRuns(null, '111', null));
    await waitFor(() => expect(result.current.runs).toEqual({ kind: 'error', message: 'No cluster or account was given.' }));
    expect(getConsolidationRuns).not.toHaveBeenCalled();
  });

  it('aborts on unmount and drops the late answer', async () => {
    let resolve: (v: ConsolidationRunResponse) => void = () => {};
    getConsolidationRuns.mockReturnValue(new Promise<ConsolidationRunResponse>((r) => { resolve = r; }));
    const { result, unmount } = renderHook(() => useConsolidationRuns('prod', '111', null));
    const signal: AbortSignal = getConsolidationRuns.mock.calls[0][1].signal;
    unmount();
    expect(signal.aborted).toBe(true);
    await act(async () => resolve(NO_RUNS));
    expect(result.current.runs.kind).toBe('loading');
  });

  it('reads again on retry', async () => {
    getConsolidationRuns.mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce(NO_RUNS);
    const { result } = renderHook(() => useConsolidationRuns('prod', '111', null));
    await waitFor(() => expect(result.current.runs).toEqual({ kind: 'error', message: 'boom' }));
    act(() => result.current.retry());
    await waitFor(() => expect(result.current.runs).toEqual({ kind: 'ok', data: NO_RUNS }));
    expect(getConsolidationRuns).toHaveBeenCalledTimes(2);
  });
});
