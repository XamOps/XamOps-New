import { describe, it, expect, afterEach, vi } from 'vitest';
import { fetchResult } from './useDbOpsData';

/**
 * fetchResult distinguishes the three outcomes the console views must branch on (UX-2): ok+data,
 * a carried HTTP status (403 → no access, >=500 → error), and a network/parse failure mapped to
 * status 0 — never collapsing them into the single `null` that fetchJson returns.
 */
describe('fetchResult', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('returns ok with data on 200', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ x: 1 }), { status: 200 })));
    const r = await fetchResult<{ x: number }>('/u');
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.data).toEqual({ x: 1 });
  });

  it('carries a 403 status (no access) without throwing', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 403 })));
    expect(await fetchResult('/u')).toEqual({ ok: false, status: 403 });
  });

  it('carries a 500 status (server error)', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 500 })));
    expect(await fetchResult('/u')).toEqual({ ok: false, status: 500 });
  });

  it('maps a network/parse failure to status 0', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('network down'); }));
    expect(await fetchResult('/u')).toEqual({ ok: false, status: 0 });
  });

  it('treats an ok empty body as ok with empty data (not an error)', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('[]', { status: 200 })));
    const r = await fetchResult<unknown[]>('/u');
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.data).toEqual([]);
  });
});
