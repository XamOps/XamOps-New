import React from 'react';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';
import type { GroupRow, Plan } from '../../../api/autospottingApi';

export interface FetchCall {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: unknown;
}

export interface Route {
  method?: string;
  match: RegExp;
  /** Responses are consumed in order; the last one repeats. */
  responses: Array<{ status?: number; body: unknown } | 'network-error'>;
}

/** Installs a global fetch that answers from `routes` and records every call. */
export function installFetch(routes: Route[]) {
  const calls: FetchCall[] = [];
  const counters = new Map<Route, number>();
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = input.toString();
    const method = (init?.method ?? 'GET').toUpperCase();
    const headers = (init?.headers ?? {}) as Record<string, string>;
    calls.push({ url, method, headers, body: init?.body ? JSON.parse(String(init.body)) : undefined });
    const route = routes.find(r => (r.method ?? 'GET') === method && r.match.test(url));
    if (!route) {
      return { ok: false, status: 404, json: async () => ({ code: 'NOT_FOUND' }), headers: new Headers() } as unknown as Response;
    }
    const i = counters.get(route) ?? 0;
    counters.set(route, i + 1);
    const r = route.responses[Math.min(i, route.responses.length - 1)];
    if (r === 'network-error') throw new TypeError('Failed to fetch');
    const status = r.status ?? 200;
    return { ok: status < 400, status, json: async () => r.body, headers: new Headers() } as unknown as Response;
  });
  vi.stubGlobal('fetch', fn);
  return { calls, fn };
}

export function renderWithProviders(ui: React.ReactElement, route = '/autospotting') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...view, client };
}

/** Last element (the tsconfig lib predates Array.prototype.at). */
export const last = <T,>(a: T[]): T => a[a.length - 1];

export const row = (over: Partial<GroupRow> = {}): GroupRow => ({
  id: 'g-1',
  provider: 'AWS',
  groupKey: 'us-east-1/web-asg',
  location: 'us-east-1',
  name: 'web-asg',
  state: 'UNMANAGED',
  managed: false,
  externalSpot: false,
  desired: 4,
  min: 1,
  max: 8,
  instanceType: 'm5.large',
  enableAllowed: true,
  enableBlockedBy: null,
  blockers: [],
  warnings: [],
  deferred: [],
  monthlySavings: 820,
  savingsUnknown: false,
  savingsLabel: 'Estimate',
  ...over,
});

export const planDto = (over: Omit<Partial<Plan>, 'body' | 'warningIds'> & { body?: unknown; warningIds?: string[] } = {}): Plan => {
  const { body, warningIds, ...rest } = over;
  return {
    planId: 'p-1',
    groupId: 'g-1',
    kind: 'ENABLE',
    status: 'OPEN',
    version: 1,
    liveFingerprint: 'fp',
    body: JSON.stringify(
      body ?? {
        current: { desired: 4 },
        target: {},
        diff: [{ field: 'onDemandBaseCapacity', from: '4', to: '1' }],
        onDemandFloor: { baseCapacity: 1, onDemandCount: 2 },
        feeBasis: { contractId: null, reason: 'NO_CONTRACT' },
        revertPath: { program: 'REVERT', method: 'RESTORE_SNAPSHOT', exact: true },
        rollout: { method: 'REFRESH', instancesToReplace: 4, surge: 1, durationRange: '10-20 min', headroom: 'ok' },
        estimates: [
          { profile: 'conservative', monthlySavings: 300, priceSources: [{ source: 'AWS', version: 'v1', asOf: '2026-11-01' }], label: 'Estimate' },
          { profile: 'balanced', monthlySavings: 450, priceSources: [{ source: 'AWS', version: 'v1', asOf: '2026-11-01' }], label: 'Estimate' },
          { profile: 'aggressive', monthlySavings: 600, priceSources: [{ source: 'AWS', version: 'v1', asOf: '2026-11-01' }], label: 'Estimate' },
        ],
      },
    ),
    requiresFullSpotOptin: false,
    warningIds: JSON.stringify(warningIds ?? []),
    dryRun: false,
    expiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
    createdAt: new Date().toISOString(),
    batchId: null,
    ...rest,
  };
};
