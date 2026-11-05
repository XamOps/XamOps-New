import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const { apiGet } = vi.hoisted(() => ({ apiGet: vi.fn() }));
vi.mock('../../utils/apiClient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../utils/apiClient')>();
  return { ...actual, default: { ...actual.default, get: apiGet } };
});

import { EksDashboardCacheProvider } from '../../contexts/EksDashboardCacheContext';
import { useEksDashboard } from '../useEksDashboard';

const wrapper = ({ children }: { children: ReactNode }) => (
  <EksDashboardCacheProvider>{children}</EksDashboardCacheProvider>
);

/** A deferred promise so a response can be resolved on demand, after a cluster switch. */
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
  version: null as string | null,
});

afterEach(() => {
  apiGet.mockReset();
  localStorage.clear();
});

describe('useEksDashboard per-cluster isolation (FEAT-004)', () => {
  it('passes an AbortSignal and applies the response for the current cluster', async () => {
    apiGet.mockResolvedValue({ clusterName: 'A', lastSyncedAt: null });
    const { result } = renderHook((p: ReturnType<typeof paramsFor>) => useEksDashboard(p), {
      wrapper,
      initialProps: paramsFor('cluster-a'),
    });
    await waitFor(() => expect(result.current.dashboard).toEqual({ clusterName: 'A', lastSyncedAt: null }));
    const [, init] = apiGet.mock.calls[0];
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  it('resets dashboard/error and the container filters on a cluster change', async () => {
    apiGet.mockResolvedValue({ clusterName: 'A', lastSyncedAt: null });
    const { result, rerender } = renderHook((p: ReturnType<typeof paramsFor>) => useEksDashboard(p), {
      wrapper,
      initialProps: paramsFor('cluster-a'),
    });
    await waitFor(() => expect(result.current.dashboard).not.toBeNull());
    act(() => {
      result.current.setContainerSearchQuery('nginx');
      result.current.setContainerNamespaceFilter('kube-system');
    });
    expect(result.current.containerSearchQuery).toBe('nginx');

    // Switch to B: the view state must clear immediately, before B's response arrives.
    apiGet.mockResolvedValue({ clusterName: 'B', lastSyncedAt: null });
    rerender(paramsFor('cluster-b'));
    expect(result.current.dashboard).toBeNull();
    expect(result.current.error).toBeNull();
    expect(result.current.containerSearchQuery).toBe('');
    expect(result.current.containerNamespaceFilter).toBe('');
    await waitFor(() => expect(result.current.dashboard).toEqual({ clusterName: 'B', lastSyncedAt: null }));
  });

  it('drops a late response from the previous cluster (A resolves after B)', async () => {
    const a = deferred<any>();
    apiGet.mockReturnValueOnce(a.promise);
    const { result, rerender } = renderHook((p: ReturnType<typeof paramsFor>) => useEksDashboard(p), {
      wrapper,
      initialProps: paramsFor('cluster-a'),
    });
    await waitFor(() => expect(apiGet).toHaveBeenCalledTimes(1));

    // Switch to B before A resolves; B resolves first.
    apiGet.mockResolvedValueOnce({ clusterName: 'B', lastSyncedAt: null });
    rerender(paramsFor('cluster-b'));
    await waitFor(() => expect(result.current.dashboard).toEqual({ clusterName: 'B', lastSyncedAt: null }));

    // The stale A response now resolves — it must be dropped, leaving B on screen.
    await act(async () => {
      a.resolve({ clusterName: 'A', lastSyncedAt: null });
    });
    expect(result.current.dashboard).toEqual({ clusterName: 'B', lastSyncedAt: null });
  });

  it('drops a late error from the previous cluster', async () => {
    const a = deferred<any>();
    apiGet.mockReturnValueOnce(a.promise);
    const { result, rerender } = renderHook((p: ReturnType<typeof paramsFor>) => useEksDashboard(p), {
      wrapper,
      initialProps: paramsFor('cluster-a'),
    });
    await waitFor(() => expect(apiGet).toHaveBeenCalledTimes(1));

    apiGet.mockResolvedValueOnce({ clusterName: 'B', lastSyncedAt: null });
    rerender(paramsFor('cluster-b'));
    await waitFor(() => expect(result.current.dashboard).toEqual({ clusterName: 'B', lastSyncedAt: null }));

    await act(async () => {
      a.reject(new Error('A failed'));
    });
    expect(result.current.error).toBeNull();
    expect(result.current.dashboard).toEqual({ clusterName: 'B', lastSyncedAt: null });
  });

  it('aborts the in-flight request on unmount', async () => {
    const a = deferred<any>();
    apiGet.mockReturnValueOnce(a.promise);
    const { unmount } = renderHook((p: ReturnType<typeof paramsFor>) => useEksDashboard(p), {
      wrapper,
      initialProps: paramsFor('cluster-a'),
    });
    await waitFor(() => expect(apiGet).toHaveBeenCalledTimes(1));
    const signal: AbortSignal = apiGet.mock.calls[0][1].signal;
    unmount();
    expect(signal.aborted).toBe(true);
  });
});
