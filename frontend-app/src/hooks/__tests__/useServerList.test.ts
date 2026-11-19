import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';

vi.mock('../../utils/apiClient', () => ({ default: { get: vi.fn() } }));

import apiClient from '../../utils/apiClient';
import { POLL_INTERVAL_MS, useDebouncedText, usePolling, useServerList } from '../useServerList';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;

/** A promise the test settles by hand. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

let visibility: DocumentVisibilityState = 'visible';

beforeEach(() => {
  visibility = 'visible';
  vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  get.mockReset();
});

const setVisibility = (next: DocumentVisibilityState) => {
  visibility = next;
  document.dispatchEvent(new Event('visibilitychange'));
};

describe('useServerList', () => {
  it('reads the URL and refetches when it changes', async () => {
    get.mockImplementation(async (url: string) => ({ url }));
    const { result, rerender } = renderHook(({ url }) => useServerList<{ url: string }>(url), {
      initialProps: { url: '/api/dspm/a?page=1' },
    });
    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.data).toEqual({ url: '/api/dspm/a?page=1' }));
    expect(result.current.loading).toBe(false);
    rerender({ url: '/api/dspm/a?page=2' });
    await waitFor(() => expect(result.current.data).toEqual({ url: '/api/dspm/a?page=2' }));
    expect(get.mock.calls.map(([url]) => url)).toEqual(['/api/dspm/a?page=1', '/api/dspm/a?page=2']);
  });

  it('requests nothing while the URL is null', () => {
    const { result } = renderHook(() => useServerList<unknown>(null));
    expect(get).not.toHaveBeenCalled();
    expect(result.current).toMatchObject({ data: null, loading: false, error: null });
  });

  it('drops an answer that arrives after a newer request started', async () => {
    const first = deferred<string>();
    const second = deferred<string>();
    get.mockImplementation((url: string) => (url.endsWith('1') ? first.promise : second.promise));
    const { result, rerender } = renderHook(({ url }) => useServerList<string>(url), { initialProps: { url: '/x?p=1' } });
    rerender({ url: '/x?p=2' });
    await act(async () => { second.resolve('second'); });
    await act(async () => { first.resolve('first'); });
    expect(result.current.data).toBe('second');
    expect(result.current.loading).toBe(false);
  });

  it('turns a failure into a DspmError and reloads on reload()', async () => {
    get.mockRejectedValueOnce(new Error('HTTP 404: {"code":"ASSET_NOT_FOUND","message":"gone"}'));
    get.mockResolvedValueOnce('again');
    const { result } = renderHook(() => useServerList<string>('/x'));
    await waitFor(() => expect(result.current.error?.code).toBe('ASSET_NOT_FOUND'));
    act(() => result.current.reload());
    await waitFor(() => expect(result.current.data).toBe('again'));
    expect(result.current.error).toBeNull();
    expect(get).toHaveBeenCalledTimes(2);
  });

  it('reads an unparseable failure as an unreachable upstream', async () => {
    get.mockRejectedValue(new Error('Network down'));
    const { result } = renderHook(() => useServerList<string>('/x'));
    await waitFor(() => expect(result.current.error?.code).toBe('UPSTREAM_UNAVAILABLE'));
  });
});

describe('useDebouncedText', () => {
  it('returns the trimmed value 300 ms after typing stops', () => {
    vi.useFakeTimers();
    const { result, rerender } = renderHook(({ value }) => useDebouncedText(value), { initialProps: { value: '' } });
    rerender({ value: ' ab' });
    act(() => { vi.advanceTimersByTime(200); });
    rerender({ value: ' abc ' });
    act(() => { vi.advanceTimersByTime(299); });
    expect(result.current).toBe('');
    act(() => { vi.advanceTimersByTime(1); });
    expect(result.current).toBe('abc');
  });
});

describe('usePolling', () => {
  it('reloads every minute while active and stops when it turns inactive', () => {
    vi.useFakeTimers();
    const reload = vi.fn();
    const { rerender } = renderHook(({ active }) => usePolling(active, reload), { initialProps: { active: true } });
    act(() => { vi.advanceTimersByTime(POLL_INTERVAL_MS - 1); });
    expect(reload).not.toHaveBeenCalled();
    act(() => { vi.advanceTimersByTime(1); });
    expect(reload).toHaveBeenCalledTimes(1);
    act(() => { vi.advanceTimersByTime(POLL_INTERVAL_MS); });
    expect(reload).toHaveBeenCalledTimes(2);
    rerender({ active: false });
    act(() => { vi.advanceTimersByTime(POLL_INTERVAL_MS * 3); });
    expect(reload).toHaveBeenCalledTimes(2);
  });

  it('never polls while inactive', () => {
    vi.useFakeTimers();
    const reload = vi.fn();
    renderHook(() => usePolling(false, reload));
    act(() => { vi.advanceTimersByTime(POLL_INTERVAL_MS * 3); });
    expect(reload).not.toHaveBeenCalled();
  });

  it('pauses while the tab is hidden and resumes when it is shown', () => {
    vi.useFakeTimers();
    const reload = vi.fn();
    renderHook(() => usePolling(true, reload));
    act(() => setVisibility('hidden'));
    act(() => { vi.advanceTimersByTime(POLL_INTERVAL_MS * 2); });
    expect(reload).not.toHaveBeenCalled();
    act(() => setVisibility('visible'));
    act(() => { vi.advanceTimersByTime(POLL_INTERVAL_MS); });
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('does not start while the tab is hidden', () => {
    vi.useFakeTimers();
    visibility = 'hidden';
    const reload = vi.fn();
    renderHook(() => usePolling(true, reload));
    act(() => { vi.advanceTimersByTime(POLL_INTERVAL_MS * 2); });
    expect(reload).not.toHaveBeenCalled();
  });

  it('stops on unmount and calls the latest reload', () => {
    vi.useFakeTimers();
    const first = vi.fn();
    const second = vi.fn();
    const { rerender, unmount } = renderHook(({ reload }) => usePolling(true, reload), { initialProps: { reload: first } });
    rerender({ reload: second });
    act(() => { vi.advanceTimersByTime(POLL_INTERVAL_MS); });
    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
    unmount();
    act(() => { vi.advanceTimersByTime(POLL_INTERVAL_MS * 2); });
    expect(second).toHaveBeenCalledTimes(1);
  });
});
