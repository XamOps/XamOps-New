import { describe, expect, it } from 'vitest';
import { buildMetricRequest, metricFormat, mixedPartition, parseFilters, type SeriesRow } from './MetricsTab';
import { sortHosts, hostsToCsv } from './HostsTab';
import { VALID_XAMPULSE_TABS, XAMPULSE_TABS, xamPulseHostPath } from '../../constants/xampulseTabs';
import type { XpHostRow } from '../../types/xampulse';

const scope = { accountIds: [1], from: '2026-01-01T00:00:00.000Z', to: '2026-01-01T01:00:00.000Z' };
const row: SeriesRow = { id: 1, metric: 'system.cpu.utilization', agg: 'avg', byHost: true, filter: 'state=steal, mountpoint!=/boot' };

describe('buildMetricRequest', () => {
  it('builds group-by and filters', () => {
    const r = buildMetricRequest(scope, [row], false, false);
    expect(r?.series[0]).toEqual({ metric: 'system.cpu.utilization', agg: 'avg', groupBy: ['instance_id'], filters: [{ k: 'state', op: '=', v: 'steal' }, { k: 'mountpoint', op: '!=', v: '/boot' }] });
    expect(r).not.toHaveProperty('compareOffsetSec');
  });
  it('adds compareOffsetSec equal to the range and the anomaly band', () => {
    const r = buildMetricRequest(scope, [row], true, true);
    expect(r?.compareOffsetSec).toBe(3600);
    expect(r?.band).toBe('ANOMALY');
  });
  it('returns null with no metric and ignores malformed filters', () => {
    expect(buildMetricRequest(scope, [{ ...row, metric: ' ' }], false, false)).toBeNull();
    expect(parseFilters('nonsense, a=~b.*')).toEqual([{ k: 'a', op: '=~', v: 'b.*' }]);
  });
});

describe('metric explorer correctness', () => {
  it('splits by a label on top of one line per host', () => {
    const r = buildMetricRequest(scope, [{ ...row, filter: '', splitBy: 'state' }], false, false);
    expect(r?.series[0].groupBy).toEqual(['instance_id', 'state']);
  });

  it('warns when a partition label (CPU state, traffic direction) would be mixed into one line', () => {
    const cpu = ['state'];
    expect(mixedPartition(cpu, { ...row, filter: '' })).toBe('state');
    expect(mixedPartition(cpu, { ...row, filter: 'state=user' })).toBeNull();
    expect(mixedPartition(cpu, { ...row, filter: '', splitBy: 'state' })).toBeNull();
    expect(mixedPartition(['device', 'direction'], { ...row, metric: 'system.network.io', agg: 'rate', filter: '' })).toBeNull();
    expect(mixedPartition(['mountpoint'], { ...row, filter: '' })).toBeNull();
  });

  it('formats only known 0–1 ratios as percentages and keeps other dimensionless metrics plain', () => {
    const ratio = metricFormat('1', 'system.cpu.utilization');
    expect([0, 0.05, 0.1, 0.15, 0.2].map(ratio)).toEqual(['0%', '5%', '10%', '15%', '20%']);
    expect(ratio(0.7853)).toBe('78.5%');
    expect(metricFormat('1', 'system.cpu.load_average.1m')(1.5)).toBe('1.5');
    const plain = metricFormat('By');
    const ticks = [0, 0.0375, 0.075, 0.1125, 0.15].map(plain);
    expect(new Set(ticks).size).toBe(5);
    expect(plain(1234567)).toBe('1,234,567');
    expect(plain(2.5)).toBe('2.5');
  });
});

const host = (name: string, health: XpHostRow['health'], cpu: number | null): XpHostRow => ({ accountId: 1, instanceId: `i-${name}`, name, region: 'r', platform: 'Linux', state: 'running', health, score: null, cpuPct: cpu, memPct: null, diskPct: null, netInBps: null, netOutBps: null, load1: null, cpuSpark: [], lastSeenAt: null, agentStatus: 'ACTIVE', collectionLevel: 'T1', tags: {} });

describe('hosts helpers', () => {
  it('sorts worst health first and by cpu', () => {
    const rows = [host('a', 'OK', 5), host('b', 'CRIT', 1), host('c', 'WARN', 99)];
    expect(sortHosts(rows, 'health', 1).map((r) => r.name)).toEqual(['b', 'c', 'a']);
    expect(sortHosts(rows, 'cpuPct', -1).map((r) => r.name)).toEqual(['c', 'a', 'b']);
  });
  it('marks demo rows in the CSV', () => {
    expect(hostsToCsv([host('web', 'OK', 1)])).not.toContain('"DEMO"');
    expect(hostsToCsv([{ ...host('demo', 'OK', 1), instanceId: 'i-demo0001' }])).toContain('"DEMO"');
  });
});

describe('tab catalog', () => {
  it('has the 8 tabs with unique xp-* access keys', () => {
    expect(XAMPULSE_TABS).toHaveLength(8);
    expect(new Set(XAMPULSE_TABS.map((t) => t.accessKey)).size).toBe(8);
    XAMPULSE_TABS.forEach((t) => expect(t.accessKey.startsWith('xp-')).toBe(true));
    expect(VALID_XAMPULSE_TABS.has('overview')).toBe(true);
    expect(VALID_XAMPULSE_TABS.has('traces')).toBe(false);
    expect(xamPulseHostPath('i-1/2', 7)).toBe('/xampulse/hosts/i-1%2F2?accountId=7');
  });
});
