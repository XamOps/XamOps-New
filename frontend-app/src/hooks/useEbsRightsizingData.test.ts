import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';

const mockFetch = vi.fn();
global.fetch = mockFetch;

import { useEbsRightsizingData } from './useEbsRightsizingData';

const makeJsonResponse = (data: unknown, ok = true) =>
  Promise.resolve({ ok, json: () => Promise.resolve(data) } as Response);

describe('initiateExpand', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockFetch.mockResolvedValue({ ok: true, json: () => Promise.resolve([]) } as unknown as Response);
  });

  afterEach(() => { vi.useRealTimers(); });

  it('calls POST /api/ebs/expand with correct body', async () => {
    mockFetch.mockImplementation((url: string, opts?: RequestInit) => {
      if (opts?.method === 'POST' && String(url).includes('/api/ebs/expand/'))
        return makeJsonResponse({ success: true, actionId: 99 });
      return makeJsonResponse([]);
    });

    const { result } = renderHook(() => useEbsRightsizingData({ selectedAccountId: 'acc-1' }));

    await act(async () => {
      await result.current.initiateExpand('vol-001', 'us-east-1', 'i-0abc', '/dev/xvdf', 300, undefined);
    });

    const expandCall = mockFetch.mock.calls.find(
      (args: any[]) => String(args[0]).includes('/api/ebs/expand/acc-1') && args[1]?.method === 'POST'
    );
    expect(expandCall).toBeDefined();
    const body = JSON.parse(expandCall![1].body as string);
    expect(body).toMatchObject({ volumeId: 'vol-001', region: 'us-east-1', instanceId: 'i-0abc', device: '/dev/xvdf', targetSizeGb: 300 });
  });

  it('passes asgName when provided', async () => {
    mockFetch.mockImplementation((url: string, opts?: RequestInit) => {
      if (opts?.method === 'POST' && String(url).includes('/api/ebs/expand/'))
        return makeJsonResponse({ success: true, actionId: 100 });
      return makeJsonResponse([]);
    });

    const { result } = renderHook(() => useEbsRightsizingData({ selectedAccountId: 'acc-1' }));

    await act(async () => {
      await result.current.initiateExpand('vol-002', 'us-east-1', 'i-0abc', '/dev/xvda', 60, 'my-asg');
    });

    const expandCall = mockFetch.mock.calls.find(
      (args: any[]) => String(args[0]).includes('/api/ebs/expand/acc-1') && args[1]?.method === 'POST'
    );
    expect(expandCall).toBeDefined();
    const body = JSON.parse(expandCall![1].body as string);
    expect(body).toMatchObject({ asgName: 'my-asg' });
  });

  it('returns error when no account selected', async () => {
    const { result } = renderHook(() => useEbsRightsizingData({ selectedAccountId: null }));

    let returnValue: any;
    await act(async () => {
      returnValue = await result.current.initiateExpand('vol-003', 'us-east-1', 'i-0abc', '/dev/xvdf', 100);
    });

    expect(returnValue).toEqual({ success: false, error: 'No account selected' });
    const expandCall = mockFetch.mock.calls.find((args: any[]) => String(args[0]).includes('/api/ebs/expand/'));
    expect(expandCall).toBeUndefined();
  });
});
