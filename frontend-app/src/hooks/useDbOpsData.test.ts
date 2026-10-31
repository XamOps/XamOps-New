import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useDbOpsData } from './useDbOpsData';

describe('useDbOpsData error state (COR-13 / UX-2)', () => {
  beforeEach(() => {
    localStorage.clear();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('surfaces an error on HTTP 500 instead of presenting a successful empty list', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: false,
      status: 500,
      json: async () => ({}),
    }));
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch);

    const { result } = renderHook(() => useDbOpsData('acct-500'));

    await waitFor(() => expect(result.current.error).toBeTruthy());

    expect(result.current.loading).toBe(false);
    // A failed load must not masquerade as an empty inventory...
    expect(result.current.inventory).toEqual([]);
    expect(result.current.summary).toBeNull();
    // ...and must not be cached.
    expect(localStorage.getItem('dbops_acct-500')).toBeNull();
    expect(fetchMock).toHaveBeenCalled();
  });

  it('treats a 200 empty inventory as a genuine empty with no error', async () => {
    const fetchMock = vi.fn(async (url: string) => ({
      ok: true,
      status: 200,
      json: async () => (url.includes('/summary') ? { totalDbMonthlyCost: 0, totalInstances: 0, totalPotentialSavings: 0, idleInstances: 0, idleSavingsPotential: 0, rightsizingCandidates: 0, rightsizingSavingsPotential: 0, snapshotWasteCost: 0, savingsPercentage: 0 } : []),
    }));
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch);

    const { result } = renderHook(() => useDbOpsData('acct-empty'));

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.error).toBeNull();
    expect(result.current.inventory).toEqual([]);
  });
});
