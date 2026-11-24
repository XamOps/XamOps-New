import { describe, it, expect, afterEach } from 'vitest';
import { vi } from 'vitest';
import {
  AutoSpottingApiError,
  NETWORK_UNREACHABLE,
  confirmPlan,
  createPlan,
  disableLegacyGroup,
  listGroups,
  mapProblem,
  newIdempotencyKey,
  parsePlanBody,
  parseWarningIds,
} from '../autospottingApi';
import { installFetch } from '../../pages/autospotting/__tests__/helpers';

afterEach(() => vi.unstubAllGlobals());

describe('autospottingApi headers', () => {
  it('sends Idempotency-Key, Content-Type and the intent header on every non-GET', async () => {
    const { calls } = installFetch([
      { method: 'POST', match: /\/plans$/, responses: [{ status: 201, body: { planId: 'p' } }] },
      { method: 'POST', match: /\/confirm/, responses: [{ status: 202, body: { operationId: 'o', status: 'QUEUED' } }] },
      { method: 'POST', match: /\/disable/, responses: [{ body: {} }] },
    ]);
    await createPlan(7, { kind: 'ENABLE', groupId: 'g' }, 'k1');
    await confirmPlan(7, 'p', { planVersion: 1, acknowledgedWarningIds: [], fullSpotOptIn: false }, 'k2');
    await disableLegacyGroup('123456789012', 'us-east-1', 'web', 'k3');
    const posts = calls.filter(c => c.method !== 'GET');
    expect(posts).toHaveLength(3);
    for (const c of posts) {
      expect(c.headers['Idempotency-Key']).toBeTruthy();
      expect(c.headers['Content-Type']).toBe('application/json');
      expect(c.headers['X-XamOps-Intent']).toBe('autospotting');
    }
    expect(posts.map(c => c.headers['Idempotency-Key'])).toEqual(['k1', 'k2', 'k3']);
  });

  it('sends no idempotency key on a GET and Cache-Control no-cache on refresh', async () => {
    const { calls } = installFetch([{ match: /\/groups$/, responses: [{ body: { items: [], partial: false, failedLocations: [] } }] }]);
    await listGroups(7, { noCache: true });
    expect(calls[0].headers['Idempotency-Key']).toBeUndefined();
    expect(calls[0].headers['Cache-Control']).toBe('no-cache');
  });

  it('retries a confirm with the same Idempotency-Key after a network error', async () => {
    const { calls } = installFetch([
      {
        method: 'POST',
        match: /\/confirm/,
        responses: ['network-error', { status: 202, body: { operationId: 'op-1', status: 'QUEUED' } }],
      },
    ]);
    const res = await confirmPlan(
      7,
      'p',
      { planVersion: 1, acknowledgedWarningIds: [], fullSpotOptIn: false },
      'same-key',
      { retryDelayMs: 0 },
    );
    expect(res.operationId).toBe('op-1');
    expect(calls).toHaveLength(2);
    expect(calls.map(c => c.headers['Idempotency-Key'])).toEqual(['same-key', 'same-key']);
  });

  it('gives up after 3 tries with NETWORK_UNREACHABLE', async () => {
    const { calls } = installFetch([{ method: 'POST', match: /\/confirm/, responses: ['network-error'] }]);
    await expect(
      confirmPlan(7, 'p', { planVersion: 1, acknowledgedWarningIds: [], fullSpotOptIn: false }, 'k', { retryDelayMs: 0 }),
    ).rejects.toMatchObject({ code: NETWORK_UNREACHABLE });
    expect(calls).toHaveLength(3);
  });

  it('does not retry an HTTP error', async () => {
    const { calls } = installFetch([
      { method: 'POST', match: /\/confirm/, responses: [{ status: 409, body: { code: 'PLAN_STALE' } }] },
    ]);
    await expect(
      confirmPlan(7, 'p', { planVersion: 1, acknowledgedWarningIds: [], fullSpotOptIn: false }, 'k', { retryDelayMs: 0 }),
    ).rejects.toMatchObject({ code: 'PLAN_STALE', status: 409 });
    expect(calls).toHaveLength(1);
  });
});

describe('problem+json mapping', () => {
  it('maps code, switch, retryable, detail and fieldErrors', () => {
    const e = mapProblem(503, {
      code: 'AS_LEASE_UNAVAILABLE',
      switch: 'GLOBAL',
      retryable: true,
      detail: 'Busy.',
      requestId: 'r-1',
      fieldErrors: [{ field: 'groupRef', message: 'invalid' }],
    });
    expect(e).toBeInstanceOf(AutoSpottingApiError);
    expect(e).toMatchObject({
      status: 503,
      code: 'AS_LEASE_UNAVAILABLE',
      switch: 'GLOBAL',
      retryable: true,
      detail: 'Busy.',
      requestId: 'r-1',
      fieldErrors: [{ field: 'groupRef', message: 'invalid' }],
    });
  });

  it('falls back to an HTTP code for a body that is not problem+json', () => {
    expect(mapProblem(502, null)).toMatchObject({ code: 'HTTP_502', retryable: false, fieldErrors: [], switch: null });
  });
});

describe('helpers', () => {
  it('generates distinct idempotency keys', () => {
    expect(newIdempotencyKey()).not.toBe(newIdempotencyKey());
  });

  it('parses plan bodies and warning ids defensively', () => {
    expect(parsePlanBody({ body: null }).estimates).toEqual([]);
    expect(parsePlanBody({ body: 'not json' }).diff).toEqual([]);
    expect(parseWarningIds({ warningIds: '["a","b"]' })).toEqual(['a', 'b']);
    expect(parseWarningIds({ warningIds: null })).toEqual([]);
  });
});
