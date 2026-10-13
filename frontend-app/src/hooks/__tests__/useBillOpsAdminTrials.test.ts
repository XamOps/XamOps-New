import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';

const mockFetch = vi.fn();
global.fetch = mockFetch;

import { useBillOpsAdminTrials } from '../useBillOpsAdminTrials';

const listBody = [
  {
    clientId: 135, companyName: 'Nowageeks', email: 'ashish@example.com',
    planType: 'TRIAL', trialStatus: 'EXPIRED', trialEndAt: '2026-10-09T12:23:57.5', daysRemaining: 0,
  },
  {
    clientId: 7, companyName: 'Lumina Cloud', email: 'billing@lumina.example',
    planType: 'PAID', trialStatus: 'CONVERTED', trialEndAt: null, daysRemaining: 0,
  },
];

/** The shape apiFetch's callers rely on: ok, status, json() and text(). */
const response = (status: number, body: unknown, bodyText?: string) => ({
  ok: status >= 200 && status < 300,
  status,
  headers: new Headers({ 'content-type': 'application/json' }),
  json: () => Promise.resolve(body),
  text: () => Promise.resolve(bodyText ?? (body == null ? '' : JSON.stringify(body))),
}) as unknown as Response;

const listCalls = () =>
  mockFetch.mock.calls.filter(([url]) => String(url).includes('/api/billops/admin/trials?'));

describe('useBillOpsAdminTrials', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockFetch.mockResolvedValue(response(200, listBody));
  });

  it('asks for converted tenants too, and sorts blocked rows first', async () => {
    const { result } = renderHook(() => useBillOpsAdminTrials());

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(listCalls()).toHaveLength(1);
    expect(String(listCalls()[0][0])).toContain('includeConverted=true');
    expect(result.current.rows.map(r => r.clientId)).toEqual([135, 7]);
    expect(result.current.error).toBeNull();
  });

  it('sends the extend days as a string, because the controller binds Map<String,String>', async () => {
    const { result } = renderHook(() => useBillOpsAdminTrials());
    await waitFor(() => expect(result.current.loading).toBe(false));

    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(String(url).includes('/extend')
        ? response(200, { clientId: 135, trialStatus: 'ACTIVE', trialEndAt: '2026-11-12T12:00:00', daysRemaining: 30 })
        : response(200, listBody)));

    await act(async () => { await result.current.extendTrial(135, 30); });

    const extendCall = mockFetch.mock.calls.find(([url]) => String(url).includes('/extend'));
    expect(extendCall).toBeDefined();
    expect(extendCall![1].method).toBe('POST');
    expect(JSON.parse(extendCall![1].body as string)).toEqual({ days: '30' });
    expect(result.current.lastResult).toMatchObject({ kind: 'extend', trialEndAt: '2026-11-12T12:00:00' });
  });

  it('refetches the list after a successful extend', async () => {
    const { result } = renderHook(() => useBillOpsAdminTrials());
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(listCalls()).toHaveLength(1);

    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(String(url).includes('/extend')
        ? response(200, { clientId: 135, trialStatus: 'ACTIVE', trialEndAt: '2026-11-12T12:00:00', daysRemaining: 30 })
        : response(200, listBody)));

    await act(async () => { await result.current.extendTrial(135, 30); });

    expect(listCalls()).toHaveLength(2);
    expect(result.current.mutatingClientId).toBeNull();
  });

  it('posts convert with no body and keeps the response', async () => {
    const { result } = renderHook(() => useBillOpsAdminTrials());
    await waitFor(() => expect(result.current.loading).toBe(false));

    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(String(url).includes('/convert')
        ? response(200, { clientId: 135, planType: 'PAID', trialStatus: 'CONVERTED' })
        : response(200, listBody)));

    await act(async () => { await result.current.convertToPaid(135); });

    const convertCall = mockFetch.mock.calls.find(([url]) => String(url).includes('/convert'));
    expect(convertCall![1].body).toBeUndefined();
    expect(result.current.lastResult).toMatchObject({ kind: 'convert', planType: 'PAID' });
  });

  it('surfaces a 402 body message verbatim instead of a generic failure', async () => {
    const { result } = renderHook(() => useBillOpsAdminTrials());
    await waitFor(() => expect(result.current.loading).toBe(false));

    const serverMessage = 'Your free trial has ended. Please contact sales to continue.';
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(String(url).includes('/extend')
        ? response(402, { error: 'TRIAL_EXPIRED', message: serverMessage })
        : response(200, listBody)));

    let ok: boolean | undefined;
    await act(async () => { ok = await result.current.extendTrial(135, 30); });

    expect(ok).toBe(false);
    expect(result.current.mutationError).toBe(serverMessage);
  });

  it('explains an empty-bodied 403 as a missing role, without redirecting the page', async () => {
    mockFetch.mockResolvedValue(response(403, null, ''));

    const { result } = renderHook(() => useBillOpsAdminTrials());
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.error).toBe('You need BillOps admin rights to manage trials.');
    expect(result.current.rows).toEqual([]);
  });

  it('reports a 404 on a mutation as a vanished tenant', async () => {
    const { result } = renderHook(() => useBillOpsAdminTrials());
    await waitFor(() => expect(result.current.loading).toBe(false));

    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(String(url).includes('/convert')
        ? response(404, null, '')
        : response(200, listBody)));

    await act(async () => { await result.current.convertToPaid(999); });

    expect(result.current.mutationError).toBe('That tenant no longer exists.');
  });
});
