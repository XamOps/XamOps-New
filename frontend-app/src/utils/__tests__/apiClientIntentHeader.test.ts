import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiFetch } from '../apiClient';

/**
 * The AutoSpotting CSRF/origin contract in the fetch interceptor (plan-phase-09.md task 9.1,
 * design-core.md §11): a non-GET to an AutoSpotting prefix carries X-XamOps-Intent: autospotting,
 * so the backend filter lets it through; a GET, and any non-AutoSpotting request, do not get the
 * header, so no other feature changes.
 */
describe('apiClient AutoSpotting intent header', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  const okResponse = (): Response =>
    ({
      status: 200,
      ok: true,
      headers: new Headers({ 'content-type': 'application/json' }),
      text: async () => '{}',
    }) as unknown as Response;

  const headerValue = (init: RequestInit | undefined, name: string): string | null => {
    const headers = init?.headers;
    if (!headers) return null;
    if (headers instanceof Headers) return headers.get(name);
    const record = headers as Record<string, string>;
    return record[name] ?? null;
  };

  beforeEach(() => {
    fetchMock = vi.fn(async () => okResponse());
    window.fetch = fetchMock as unknown as typeof fetch;
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('adds the intent header on a non-GET to a v2 prefix', async () => {
    await apiFetch('/api/autospotting/v2/accounts/1/plans', {
      method: 'POST',
      body: JSON.stringify({ kind: 'ENABLE' }),
    });
    const [, init] = fetchMock.mock.calls[0];
    expect(headerValue(init, 'X-XamOps-Intent')).toBe('autospotting');
  });

  it('adds the intent header on a PUT to the operator prefix', async () => {
    await apiFetch('/api/xamops/autospotting-admin/switches/GLOBAL', {
      method: 'PUT',
      body: JSON.stringify({ engaged: true, reason: 'x', expectedVersion: 0 }),
    });
    const [, init] = fetchMock.mock.calls[0];
    expect(headerValue(init, 'X-XamOps-Intent')).toBe('autospotting');
  });

  it('adds the intent header on a legacy AutoSpotting non-GET', async () => {
    await apiFetch('/api/autospotting/disable', { method: 'POST', body: '{}' });
    const [, init] = fetchMock.mock.calls[0];
    expect(headerValue(init, 'X-XamOps-Intent')).toBe('autospotting');
  });

  it('does not add the header on a GET to an AutoSpotting prefix', async () => {
    await apiFetch('/api/autospotting/v2/config', { method: 'GET' });
    const [, init] = fetchMock.mock.calls[0];
    expect(headerValue(init, 'X-XamOps-Intent')).toBeNull();
  });

  it('does not add the header on a non-AutoSpotting non-GET', async () => {
    await apiFetch('/api/xamops/cost/refresh', { method: 'POST', body: '{}' });
    const [, init] = fetchMock.mock.calls[0];
    expect(headerValue(init, 'X-XamOps-Intent')).toBeNull();
  });
});
