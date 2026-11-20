import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useReservationData } from '../useReservationData';

/**
 * Changing a filter on the Reservation page used to start two recommendation requests. The
 * second cancelled the first, and the cancelled one's `finally` turned the loading flag off
 * while the second was still running, so the empty table showed "Fully Optimized" for a moment.
 */

const ACCOUNT = '605134457560';

const PAGE_DATA = {
  analysis: { utilizationPercentage: 0, coveragePercentage: 0 },
  historicalData: { labels: [], utilizationPercentages: [], coveragePercentages: [] },
  inventory: [],
  savingsPlans: [],
  purchaseRecommendations: [],
  savingsPlanRecommendations: [],
};

const okResponse = (body: unknown): Response =>
  ({
    ok: true,
    status: 200,
    headers: new Headers({ 'X-Recommendations-Complete': 'true' }),
    json: async () => body,
  }) as unknown as Response;

/** Lets pending promise callbacks, such as a cancelled request's catch and finally, run. */
const settle = () =>
  act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });

describe('useReservationData loading flags', () => {
  /** Answers for recommendation requests the test has not resolved yet, oldest first. */
  let pending: Array<(body: unknown) => void>;

  beforeEach(() => {
    sessionStorage.clear();
    pending = [];
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        // Page data answers at once. RI and Savings Plans recommendation requests wait for the test.
        if (!url.includes('recommendations?')) return Promise.resolve(okResponse(PAGE_DATA));
        return new Promise<Response>((resolve, reject) => {
          init?.signal?.addEventListener('abort', () =>
            reject(new DOMException('The operation was aborted.', 'AbortError')),
          );
          pending.push((body) => resolve(okResponse(body)));
        });
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const renderLoaded = async () => {
    const hook = renderHook(() => useReservationData({ selectedAccountId: ACCOUNT }));
    await waitFor(() => expect(hook.result.current.isLoading).toBe(false));
    return hook;
  };

  /** Answers a waiting request and lets the hook finish handling it. */
  const answer = (index: number, body: unknown) =>
    act(async () => {
      pending[index](body);
      await new Promise((r) => setTimeout(r, 0));
    });

  it('keeps the RI loading flag on until the request that replaced a cancelled one answers', async () => {
    const { result } = await renderLoaded();

    act(() => {
      void result.current.fetchPurchaseRecommendations(true);
    });
    act(() => {
      void result.current.fetchPurchaseRecommendations(true);
    });
    await settle();

    expect(pending).toHaveLength(2);
    expect(result.current.isRecsLoading).toBe(true);

    await answer(1, [{ instanceFamily: 't3' }]);

    expect(result.current.isRecsLoading).toBe(false);
    expect(result.current.data.purchaseRecommendations).toEqual([{ instanceFamily: 't3' }]);
  });

  it('turns the RI loading flag off when the browser cache answers', async () => {
    const { result } = await renderLoaded();

    act(() => {
      void result.current.fetchPurchaseRecommendations(true);
    });
    sessionStorage.setItem(
      `reservationRecs-${ACCOUNT}-ONE_YEAR-NO_UPFRONT-THIRTY_DAYS-STANDARD`,
      JSON.stringify({ data: [{ instanceFamily: 't2' }], timestamp: Date.now(), version: 'v1' }),
    );
    act(() => {
      void result.current.fetchPurchaseRecommendations(false);
    });
    await settle();

    expect(result.current.isRecsLoading).toBe(false);
    expect(result.current.data.purchaseRecommendations).toEqual([{ instanceFamily: 't2' }]);
  });

  it('keeps the Savings Plans loading flag on until the request that replaced a cancelled one answers', async () => {
    const { result } = await renderLoaded();

    act(() => {
      void result.current.fetchSpRecommendations(true);
    });
    act(() => {
      void result.current.fetchSpRecommendations(true);
    });
    await settle();

    expect(pending).toHaveLength(2);
    expect(result.current.isSpRecsLoading).toBe(true);

    await answer(1, [{ savingsPlanType: 'COMPUTE_SP' }]);

    expect(result.current.isSpRecsLoading).toBe(false);
    expect(result.current.data.savingsPlanRecommendations).toEqual([{ savingsPlanType: 'COMPUTE_SP' }]);
  });
});
