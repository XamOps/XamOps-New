import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { REQUIRED_KEYS, missingKeys } from '../xampulseGuards';
import { demoRequest } from '../../demo/xampulseDemo';

// Written by the Java side (XamPulseContractSampleTest) when it exists: <TypeName>.json per response type.
const SAMPLES = resolve(__dirname, '../../../../xamops-service/src/test/resources/xampulse/contract-samples');

describe('Java contract samples satisfy the TS guards', () => {
  const files = existsSync(SAMPLES) ? readdirSync(SAMPLES).filter((f) => f.endsWith('.json')) : [];
  if (files.length === 0) {
    it.skip('no contract-samples directory yet (the Java sample test has not been written)', () => undefined);
  }
  it.each(files)('%s', (f) => {
    const type = f.replace(/\.json$/, '');
    const body: unknown = JSON.parse(readFileSync(resolve(SAMPLES, f), 'utf8'));
    expect(missingKeys(type, body)).toEqual([]);
  });
});

// The demo fixtures are typed against types/xampulse.ts, so this also proves the guards match the TS contract.
describe('demo fixtures satisfy the guards', () => {
  const now = new Date();
  const from = new Date(now.getTime() - 3_600_000).toISOString();
  const to = now.toISOString();
  const scope = { accountIds: [1], from, to };
  it('overview, hosts, logs, metrics, alerts, trust, setup, usage', async () => {
    expect(missingKeys('XpOverview', await demoRequest('GET', '/overview', {}))).toEqual([]);
    const hosts = await demoRequest<{ items: unknown[] }>('GET', '/hosts', {});
    expect(missingKeys('XpHostRow', hosts.items[0])).toEqual([]);
    expect(missingKeys('XpHostSummary', await demoRequest('GET', '/hosts/i-demo0001/summary', { from, to }))).toEqual([]);
    const search = await demoRequest<{ rows: unknown[] }>('POST', '/logs/search', undefined, { scope, query: '' });
    expect(missingKeys('XpLogSearchResponse', search)).toEqual([]);
    expect(missingKeys('XpLogRow', search.rows[0])).toEqual([]);
    expect(missingKeys('XpLogHistogramResponse', await demoRequest('POST', '/logs/histogram', undefined, { scope, query: '' }))).toEqual([]);
    expect(missingKeys('XpLogFacetsResponse', await demoRequest('POST', '/logs/facets', undefined, { scope, query: '', fields: ['service'] }))).toEqual([]);
    expect(missingKeys('XpLogPatternsResponse', await demoRequest('POST', '/logs/patterns', undefined, { scope, query: '' }))).toEqual([]);
    expect(missingKeys('XpMetricQueryResponse', await demoRequest('POST', '/metrics/query', undefined, { scope, series: [{ metric: 'system.cpu.utilization', agg: 'avg' }] }))).toEqual([]);
    const rules = await demoRequest<unknown[]>('GET', '/alerts/rules');
    expect(missingKeys('XpAlertRule', rules[0])).toEqual([]);
    const inc = await demoRequest<{ items: unknown[] }>('GET', '/alerts/incidents', {});
    expect(missingKeys('XpIncident', inc.items[0])).toEqual([]);
    const ins = await demoRequest<unknown[]>('GET', '/insights/feed', { from, to });
    expect(missingKeys('XpInsight', ins[0])).toEqual([]);
    expect(missingKeys('XpTrustPanel', await demoRequest('GET', '/trust', {}))).toEqual([]);
    expect(missingKeys('XpSetupOverview', await demoRequest('GET', '/accounts/1/setup'))).toEqual([]);
    expect(missingKeys('XpUsage', await demoRequest('GET', '/usage', {}))).toEqual([]);
    const accts = await demoRequest<unknown[]>('GET', '/accounts');
    expect(missingKeys('XpAccountRef', accts[0])).toEqual([]);
  });
  it('flags drift', () => {
    expect(missingKeys('XpOverview', { demo: true })).toContain('kpis');
    expect(Object.keys(REQUIRED_KEYS).length).toBeGreaterThan(10);
  });
});
