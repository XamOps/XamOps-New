import { describe, expect, it } from 'vitest';
import { buildMetricRequest, parseFilters, type SeriesRow } from './MetricsTab';
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
    expect(xamPulseHostPath('i-1/2')).toBe('/xampulse/hosts/i-1%2F2');
  });
});
