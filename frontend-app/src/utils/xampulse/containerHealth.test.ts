import { describe, expect, it } from 'vitest';
import { containerRowHealth, memoryShare, needsAttention } from './containerHealth';
import type { XpContainerRow } from '../../types/xampulse';

const NOW = Date.parse('2026-11-23T12:00:00Z');
const row = (o: Partial<XpContainerRow> = {}): XpContainerRow => ({
  accountId: 1, instanceId: 'i-1', containerId: 'c', name: 'svc', cpuPct: 10, memBytes: 1e9, memLimitBytes: 4e9, memLimitIsHost: false,
  restarts: 0, errors: 0, lastSeenAt: new Date(NOW - 15_000).toISOString(), ...o,
});

describe('containerRowHealth', () => {
  it('is healthy with headroom, no restarts and no errors', () => {
    const h = containerRowHealth(row(), NOW);
    expect(h).toMatchObject({ state: 'OK', health: 'OK', label: 'Healthy', reasons: [] });
    expect(needsAttention(h)).toBe(false);
  });

  it('is critical at 90% of a real memory limit and lists every reason, worst first', () => {
    const h = containerRowHealth(row({ memBytes: 3.64e9, restarts: 3, errors: 19 }), NOW);
    expect(h.state).toBe('CRIT');
    expect(h.reasons).toEqual(['memory 91% of limit', '3 restarts', '19 errors in 15 min']);
  });

  it('warns at 75% of the limit, on one restart or on any error', () => {
    expect(containerRowHealth(row({ memBytes: 3.1e9 }), NOW).state).toBe('WARN');
    expect(containerRowHealth(row({ restarts: 1 }), NOW).reasons).toEqual(['1 restart']);
    expect(containerRowHealth(row({ errors: 1 }), NOW).reasons).toEqual(['1 error in 15 min']);
  });

  it('never treats a host-sized limit as memory pressure', () => {
    const r = row({ memBytes: 7.9e9, memLimitBytes: 8e9, memLimitIsHost: true });
    expect(memoryShare(r)).toBeNull();
    expect(containerRowHealth(r, NOW).state).toBe('OK');
  });

  it('says Not reporting when the last sample is over five minutes before the range end', () => {
    const h = containerRowHealth(row({ lastSeenAt: new Date(NOW - 6 * 60_000).toISOString(), restarts: 4 }), NOW);
    expect(h).toMatchObject({ state: 'STALE', health: 'NO_DATA', label: 'Not reporting' });
    expect(needsAttention(h)).toBe(true);
  });

  it('is No data, never Healthy, without CPU or memory samples', () => {
    expect(containerRowHealth(row({ cpuPct: null, memBytes: null }), NOW).state).toBe('NO_DATA');
  });
});
