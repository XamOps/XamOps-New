import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useInsightRead } from '../useInsightRead';
import type { InsightReadResult } from '../../types/automationInsights';

type R = InsightReadResult<{ n: number }>;

/** A read the test answers by hand, recording the signal it was given. */
function deferredRun() {
  const calls: Array<{ signal: AbortSignal; resolve: (r: R) => void }> = [];
  const run = vi.fn((signal: AbortSignal) => new Promise<R>((resolve) => calls.push({ signal, resolve })));
  return { run, calls };
}

describe('useInsightRead: one cancellable read per key, no polling', () => {
  it('reads nothing for a null key', () => {
    const { run } = deferredRun();
    const { result } = renderHook(() => useInsightRead(null, run));
    expect(run).not.toHaveBeenCalled();
    expect(result.current).toMatchObject({ loading: false, result: null });
  });

  it('loads, then shows the answer for its key', async () => {
    const { run, calls } = deferredRun();
    const { result } = renderHook(() => useInsightRead('a', run));
    expect(result.current.loading).toBe(true);
    await act(async () => calls[0].resolve({ kind: 'ok', view: { n: 1 } }));
    expect(result.current).toMatchObject({ loading: false, result: { kind: 'ok', view: { n: 1 } } });
    expect(run).toHaveBeenCalledTimes(1);
  });

  it('aborts the read in flight when the key changes, and never shows its answer under the new key', async () => {
    const { run, calls } = deferredRun();
    const { result, rerender } = renderHook(({ k }) => useInsightRead(k, run), { initialProps: { k: 'cpu' } });
    rerender({ k: 'memory' });
    expect(calls[0].signal.aborted).toBe(true);
    await act(async () => calls[0].resolve({ kind: 'ok', view: { n: 1 } }));
    expect(result.current).toMatchObject({ loading: true, result: null });
    await act(async () => calls[1].resolve({ kind: 'ok', view: { n: 2 } }));
    expect(result.current.result).toEqual({ kind: 'ok', view: { n: 2 } });
  });

  it('aborts on unmount', () => {
    const { run, calls } = deferredRun();
    const { unmount } = renderHook(() => useInsightRead('a', run));
    unmount();
    expect(calls[0].signal.aborted).toBe(true);
  });

  it('reads again on reload (Retry), and shows a thrown read as an error', async () => {
    const run = vi.fn<(signal: AbortSignal) => Promise<R>>().mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce({ kind: 'ok', view: { n: 3 } });
    const { result } = renderHook(() => useInsightRead('a', run));
    await waitFor(() => expect(result.current.result).toEqual({ kind: 'error', message: 'boom' }));
    act(() => result.current.reload());
    await waitFor(() => expect(result.current.result).toEqual({ kind: 'ok', view: { n: 3 } }));
    expect(run).toHaveBeenCalledTimes(2);
  });
});
