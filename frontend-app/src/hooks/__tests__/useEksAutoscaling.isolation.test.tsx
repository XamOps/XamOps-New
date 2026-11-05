import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const { apiGet, apiPut } = vi.hoisted(() => ({ apiGet: vi.fn(), apiPut: vi.fn() }));
vi.mock('../../utils/apiClient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../utils/apiClient')>();
  return { ...actual, default: { ...actual.default, get: apiGet, put: apiPut } };
});

import { useEksAutoscaling } from '../useEksAutoscaling';
import type { ScaleOutPolicy } from '../../types/eksTypes';

function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

const paramsFor = (name: string, account = '111') => ({
  clusterName: name,
  region: 'us-east-1',
  accountId: account,
});

const dashboardFor = (tag: string) => ({ agentStatus: { tag } }) as any;

afterEach(() => {
  apiGet.mockReset();
  apiPut.mockReset();
});

describe('useEksAutoscaling per-cluster isolation (FEAT-004)', () => {
  it('resets the read state on a cluster change', async () => {
    apiGet.mockResolvedValue(dashboardFor('A'));
    const { result, rerender } = renderHook((p: ReturnType<typeof paramsFor>) => useEksAutoscaling(p), {
      initialProps: paramsFor('cluster-a'),
    });
    await waitFor(() => expect(result.current.autoscalerData).not.toBeNull());

    apiGet.mockResolvedValue(dashboardFor('B'));
    rerender(paramsFor('cluster-b'));
    // Reset-on-key-change clears the previous cluster's data immediately.
    expect(result.current.autoscalerData).toBeNull();
    expect(result.current.rightsizingData).toBeNull();
    expect(result.current.evictorCandidates).toEqual([]);
    expect(result.current.liveRebalanceStatus).toBeNull();
    expect(result.current.nodeFleetReport).toBeNull();
    await waitFor(() => expect(result.current.autoscalerData).toEqual(dashboardFor('B')));
  });

  it('an in-flight policy save still resolves its toast and clears isSaving when the cluster changes', async () => {
    apiGet.mockResolvedValue(dashboardFor('A'));
    const put = deferred<void>();
    apiPut.mockReturnValueOnce(put.promise);

    const { result, rerender } = renderHook((p: ReturnType<typeof paramsFor>) => useEksAutoscaling(p), {
      initialProps: paramsFor('cluster-a'),
    });
    await waitFor(() => expect(result.current.autoscalerData).not.toBeNull());

    let savePromise: Promise<void>;
    act(() => {
      savePromise = result.current.saveScaleOutPolicy({ enabled: true } as ScaleOutPolicy);
    });
    await waitFor(() => expect(result.current.isSaving).toBe(true));

    // The cluster changes mid-save; the save must still complete (no abort/token guard here).
    apiGet.mockResolvedValue(dashboardFor('B'));
    rerender(paramsFor('cluster-b'));

    await act(async () => {
      put.resolve();
      await savePromise!;
    });
    expect(result.current.isSaving).toBe(false);
    expect(result.current.saveSuccess).toBe('Scale-Out policy saved');
  });
});
