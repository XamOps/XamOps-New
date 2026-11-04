import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { get } = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('../../utils/apiClient', () => ({ default: { get } }));

import { clearK8sCaches, k8sCacheKey, userKey } from '../../utils/k8sCache';
import { useK8sClusters } from '../useK8sClusters';

const LIST = [
  { name: 'prod', region: 'us-east-1', version: '1.30', connected: true },
  { name: 'stage', region: 'eu-west-1', version: '1.29', connected: false },
];

describe('useK8sClusters', () => {
  beforeEach(() => {
    get.mockReset();
    clearK8sCaches();
  });

  it('keys its cache by account only', () => {
    expect(k8sCacheKey({ accountId: '111' })).toBe(`${userKey()}|111||`);
  });

  it('requests the fleet list once per account within the TTL', async () => {
    get.mockResolvedValue(LIST);
    const first = renderHook(() => useK8sClusters('111'));
    await waitFor(() => expect(first.result.current.status).toBe('ready'));
    expect(get).toHaveBeenCalledWith('/api/xamops/k8s/clusters?accountIds=111&forceRefresh=false');
    first.unmount();

    const second = renderHook(() => useK8sClusters('111'));
    expect(second.result.current.status).toBe('ready');
    expect(second.result.current.clusters).toHaveLength(2);
    expect(get).toHaveBeenCalledTimes(1);
  });

  it('refetches after the caches are cleared', async () => {
    get.mockResolvedValue(LIST);
    const first = renderHook(() => useK8sClusters('111'));
    await waitFor(() => expect(first.result.current.status).toBe('ready'));
    first.unmount();
    clearK8sCaches();
    const second = renderHook(() => useK8sClusters('111'));
    await waitFor(() => expect(second.result.current.status).toBe('ready'));
    expect(get).toHaveBeenCalledTimes(2);
  });

  it('drops invalid items, de-duplicates by name+region and keeps same-name clusters in other regions', async () => {
    get.mockResolvedValue([
      ...LIST,
      { name: 'prod', region: 'us-east-1' },
      { name: 'prod', region: 'eu-west-1' },
      { name: '-bad', region: 'us-east-1' },
      { name: 'ok', region: 'nowhere' },
      null,
      'x',
    ]);
    const { result } = renderHook(() => useK8sClusters('111'));
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(result.current.clusters.map((c) => `${c.name}|${c.region}`)).toEqual([
      'prod|us-east-1', 'stage|eu-west-1', 'prod|eu-west-1',
    ]);
  });

  it('is an error for a non-array body or a thrown request, and reload retries', async () => {
    get.mockResolvedValueOnce({ not: 'an array' });
    const { result } = renderHook(() => useK8sClusters('111'));
    await waitFor(() => expect(result.current.status).toBe('error'));
    get.mockRejectedValueOnce(new Error('boom'));
    act(() => result.current.reload());
    await waitFor(() => expect(get).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(result.current.status).toBe('error'));
    get.mockResolvedValueOnce(LIST);
    act(() => result.current.reload());
    await waitFor(() => expect(result.current.status).toBe('ready'));
  });

  it('ignores a response that lands after the account changed', async () => {
    let resolveFirst!: (v: unknown) => void;
    get.mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve; }));
    get.mockResolvedValueOnce([{ name: 'other', region: 'us-east-1' }]);
    const { result, rerender } = renderHook(({ id }) => useK8sClusters(id), { initialProps: { id: '111' } });
    rerender({ id: '222' });
    await waitFor(() => expect(result.current.status).toBe('ready'));
    await act(async () => resolveFirst(LIST));
    expect(result.current.clusters.map((c) => c.name)).toEqual(['other']);
  });
});
