import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const { apiGet } = vi.hoisted(() => ({ apiGet: vi.fn() }));
vi.mock('../../utils/apiClient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../utils/apiClient')>();
  return { ...actual, default: { ...actual.default, get: apiGet } };
});

import { useEksClusterCost } from '../useEksClusterCost';

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const paramsFor = (name: string, account = '111') => ({
  clusterName: name,
  accountId: account,
  region: 'us-east-1',
});

const engineBody = (tag: string) => ({
  cost: { monthlyCost: tag } as any,
  workloads: [{ name: tag } as any],
  engine: { tag } as any,
});

afterEach(() => apiGet.mockReset());

describe('useEksClusterCost per-cluster isolation (FEAT-004)', () => {
  it('passes an AbortSignal and applies the response for the current cluster', async () => {
    apiGet.mockResolvedValue(engineBody('A'));
    const { result } = renderHook((p: ReturnType<typeof paramsFor>) => useEksClusterCost(p), {
      initialProps: paramsFor('cluster-a'),
    });
    await waitFor(() => expect(result.current.cost).toEqual({ monthlyCost: 'A' }));
    const [, init] = apiGet.mock.calls[0];
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  it('clears cost/workloads/engine on a cluster change, before the next response', async () => {
    apiGet.mockResolvedValue(engineBody('A'));
    const { result, rerender } = renderHook((p: ReturnType<typeof paramsFor>) => useEksClusterCost(p), {
      initialProps: paramsFor('cluster-a'),
    });
    await waitFor(() => expect(result.current.cost).not.toBeNull());

    apiGet.mockResolvedValue(engineBody('B'));
    rerender(paramsFor('cluster-b'));
    expect(result.current.cost).toBeNull();
    expect(result.current.workloads).toEqual([]);
    expect(result.current.engine).toBeUndefined();
    await waitFor(() => expect(result.current.cost).toEqual({ monthlyCost: 'B' }));
  });

  it('drops a late response from the previous cluster (A resolves after B)', async () => {
    const a = deferred<any>();
    apiGet.mockReturnValueOnce(a.promise);
    const { result, rerender } = renderHook((p: ReturnType<typeof paramsFor>) => useEksClusterCost(p), {
      initialProps: paramsFor('cluster-a'),
    });
    await waitFor(() => expect(apiGet).toHaveBeenCalledTimes(1));

    apiGet.mockResolvedValueOnce(engineBody('B'));
    rerender(paramsFor('cluster-b'));
    await waitFor(() => expect(result.current.cost).toEqual({ monthlyCost: 'B' }));

    await act(async () => {
      a.resolve(engineBody('A'));
    });
    expect(result.current.cost).toEqual({ monthlyCost: 'B' });
    expect(result.current.workloads).toEqual([{ name: 'B' }]);
  });

  it('aborts the in-flight request on unmount', async () => {
    const a = deferred<any>();
    apiGet.mockReturnValueOnce(a.promise);
    const { unmount } = renderHook((p: ReturnType<typeof paramsFor>) => useEksClusterCost(p), {
      initialProps: paramsFor('cluster-a'),
    });
    await waitFor(() => expect(apiGet).toHaveBeenCalledTimes(1));
    const signal: AbortSignal = apiGet.mock.calls[0][1].signal;
    unmount();
    expect(signal.aborted).toBe(true);
  });
});
