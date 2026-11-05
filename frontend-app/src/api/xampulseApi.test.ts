import { afterEach, describe, expect, it, vi } from 'vitest';
import { XpApiError, parseErrorBody, toQueryString, xampulseApi } from './xampulseApi';
import { describeRule } from './xampulseAlertsApi';
import { blankRule, validateRule } from '../components/XamPulse/alerts/RuleEditor';

afterEach(() => { vi.unstubAllGlobals(); });

describe('error mapping', () => {
  it('maps the XpErrorEnvelope', () => {
    const e = parseErrorBody(JSON.stringify({ error: { code: 'XP_NOT_SET_UP', message: 'Not set up', requestId: 'req-1' } }), 409);
    expect(e).toBeInstanceOf(XpApiError);
    expect(e).toMatchObject({ code: 'XP_NOT_SET_UP', status: 409, requestId: 'req-1' });
  });
  it('maps non-JSON bodies by status', () => {
    expect(parseErrorBody('<html>', 403).code).toBe('XP_FORBIDDEN');
    expect(parseErrorBody('', 500).code).toBe('XP_UNKNOWN');
  });
});

describe('query string', () => {
  it('joins arrays and skips empties', () => {
    expect(toQueryString({ accountIds: [1, 2], q: '', x: undefined, a: 'b c' })).toBe('?accountIds=1%2C2&a=b+c');
  });
});

describe('xpRequest over fetch', () => {
  it('surfaces a 409 XP_NOT_SET_UP as XpApiError', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ error: { code: 'XP_NOT_SET_UP', message: 'x', requestId: 'r' } }), { status: 409, headers: { 'Content-Type': 'application/json' } })));
    await expect(xampulseApi.overview([1], '2026-01-01T00:00:00Z', '2026-01-01T01:00:00Z')).rejects.toMatchObject({ code: 'XP_NOT_SET_UP', status: 409 });
  });
  it('returns parsed JSON on success', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ enabled: true, demoAvailable: false }), { status: 200, headers: { 'Content-Type': 'application/json' } })));
    await expect(xampulseApi.status()).resolves.toEqual({ enabled: true, demoAvailable: false });
  });
});

describe('alert rule helpers', () => {
  it('describes every condition type in plain words', () => {
    const r = blankRule(1);
    expect(describeRule({ ...r, name: 'x' })).toBe('Alert HIGH when avg system.cpu.utilization is > 90 over 5m for 10m on all hosts');
    expect(describeRule({ ...r, condition: { ...r.condition, type: 'HEARTBEAT_MISSING' } })).toMatch(/stop reporting/);
    expect(describeRule({ ...r, condition: { ...r.condition, type: 'LOG_COUNT', query: 'severity:error' } })).toMatch(/matching logs/);
    expect(describeRule({ ...r, condition: { ...r.condition, type: 'METRIC_ANOMALY' } })).toMatch(/normal band/);
    expect(describeRule({ ...r, condition: { ...r.condition, type: 'FORECAST', forecastDays: 5 } })).toMatch(/within 5 days/);
  });
  it('validates names, windows and thresholds', () => {
    const r = blankRule(1);
    expect(validateRule(r)).toContain('Give the rule a name.');
    expect(validateRule({ ...r, name: 'ok' })).toEqual([]);
    expect(validateRule({ ...r, name: 'ok', condition: { ...r.condition, window: 'five' } })).toHaveLength(1);
  });
});
