import React from 'react';
import { describe, it, expect, afterEach } from 'vitest';
import { vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { installFetch, last } from '../../pages/autospotting/__tests__/helpers';
import { useAutoSpottingConfig } from './useAutoSpottingConfig';
import { useSpotGroups } from './useSpotGroups';
import { useOperation, operationPollInterval } from './useOperation';

afterEach(() => vi.unstubAllGlobals());

const wrapper = () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const W: React.FC<{ children: React.ReactNode }> = ({ children }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return W;
};

describe('operationPollInterval', () => {
  it('polls 2 s while running or queued and 10 s while waiting', () => {
    expect(operationPollInterval('RUNNING')).toBe(2000);
    expect(operationPollInterval('QUEUED')).toBe(2000);
    expect(operationPollInterval('WAITING')).toBe(10000);
  });

  it('stops on every terminal status', () => {
    for (const s of ['SUCCEEDED', 'FAILED', 'CANCELLED']) {
      expect(operationPollInterval(s)).toBe(false);
    }
  });

  it('slows to 60 s in a hidden tab and backs off 5 s to 60 s on errors', () => {
    expect(operationPollInterval('RUNNING', { hidden: true })).toBe(60000);
    expect(operationPollInterval('RUNNING', { errorCount: 1 })).toBe(5000);
    expect(operationPollInterval('RUNNING', { errorCount: 2 })).toBe(10000);
    expect(operationPollInterval('RUNNING', { errorCount: 9 })).toBe(60000);
  });
});

describe('useAutoSpottingConfig', () => {
  it('reads the v2 config', async () => {
    installFetch([{ match: /\/v2\/config$/, responses: [{ body: { shellEnabled: true, dryRun: false, providers: {}, engagedSwitches: [] } }] }]);
    const { result } = renderHook(() => useAutoSpottingConfig(), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.data?.shellEnabled).toBe(true));
  });
});

describe('useSpotGroups', () => {
  it('keeps the partial flag and the failed locations', async () => {
    installFetch([
      {
        match: /\/accounts\/7\/groups$/,
        responses: [
          {
            body: {
              items: [],
              partial: true,
              failedLocations: [{ location: 'ap-east-1', code: 'TIMEOUT' }],
              asOf: '2026-11-17T00:00:00Z',
            },
          },
        ],
      },
    ]);
    const { result } = renderHook(() => useSpotGroups(7), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.data).toBeTruthy());
    expect(result.current.data?.partial).toBe(true);
    expect(result.current.data?.failedLocations).toEqual([{ location: 'ap-east-1', code: 'TIMEOUT' }]);
  });

  it('is disabled without an account id', () => {
    const { fn } = installFetch([]);
    renderHook(() => useSpotGroups(null), { wrapper: wrapper() });
    expect(fn).not.toHaveBeenCalled();
  });

  it('refresh asks the backend to bypass its cache', async () => {
    const { calls } = installFetch([{ match: /\/groups$/, responses: [{ body: { items: [], partial: false, failedLocations: [] } }] }]);
    const { result } = renderHook(() => useSpotGroups(7), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.data).toBeTruthy());
    await result.current.refresh();
    expect(last(calls).headers['Cache-Control']).toBe('no-cache');
  });
});

describe('useOperation', () => {
  it('reads a terminal operation once and does not poll again', async () => {
    const { calls } = installFetch([
      { match: /\/operations\/op-1$/, responses: [{ body: { operationId: 'op-1', status: 'SUCCEEDED', phase: 'FORWARD', step: 3 } }] },
    ]);
    const { result } = renderHook(() => useOperation(7, 'op-1'), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.terminal).toBe(true));
    await new Promise(r => setTimeout(r, 150));
    expect(calls.filter(c => /operations/.test(c.url))).toHaveLength(1);
  });
});
