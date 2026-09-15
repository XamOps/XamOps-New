import { describe, it, expect } from 'vitest';
import { severityTotal, SEVERITY_ORDER } from '../../types/xamsecure';
import { xamSecureCacheKey, applySyncStamps } from '../useXamSecureData';
import type { SyncStatus } from '../../types/xamsecure';

const syncStatus = (overrides: Record<string, string | null> = {}): SyncStatus => ({
  status: 'OK',
  oldestSuccessAt: '2026-09-07T00:00:00Z',
  serverTime: '2026-09-07T06:00:00Z',
  domains: ['DETECTIONS', 'VULNERABILITIES', 'POSTURE', 'INVENTORY', 'API_ENDPOINTS'].map((d) => ({
    domain: d,
    lastSuccessAt: d in overrides ? overrides[d] : '2026-09-07T00:00:00Z',
    ageSeconds: 0,
    stale: false,
    failing: false,
    lastError: null,
  })),
});

describe('xamsecure helpers', () => {
  it('severityTotal sums all severities', () => {
    expect(severityTotal({ CRITICAL: 2, HIGH: 3 })).toBe(5);
    expect(severityTotal(undefined)).toBe(0);
  });

  it('severity order is fixed CRITICAL-first', () => {
    expect(SEVERITY_ORDER[0]).toBe('CRITICAL');
  });

  it('cache key is scoped per account', () => {
    expect(xamSecureCacheKey('summary', '111')).toContain('|summary|111|');
    expect(xamSecureCacheKey('summary', null)).toContain('|summary|all|');
    expect(xamSecureCacheKey('summary', '111')).not.toBe(xamSecureCacheKey('summary', '222'));
  });

  /**
   * A section fed by a synced table is keyed by that sync's completion time, so the entry stays
   * valid until the sync actually moves rather than expiring on a timer.
   */
  it('keys stored sections by the owning sync stamp', () => {
    applySyncStamps(syncStatus());
    const first = xamSecureCacheKey('vuln_dashboard', '111');

    // Same sync -> same key -> the cached payload is still reachable.
    applySyncStamps(syncStatus());
    expect(xamSecureCacheKey('vuln_dashboard', '111')).toBe(first);

    // An unrelated domain moving must not invalidate it.
    applySyncStamps(syncStatus({ POSTURE: '2026-09-07T05:00:00Z' }));
    expect(xamSecureCacheKey('vuln_dashboard', '111')).toBe(first);

    // Its own domain moving must.
    const changed = applySyncStamps(syncStatus({ VULNERABILITIES: '2026-09-07T06:00:00Z' }));
    expect(changed).toBe(true);
    expect(xamSecureCacheKey('vuln_dashboard', '111')).not.toBe(first);
  });

  it('parameterised sections inherit their domain stamp', () => {
    applySyncStamps(syncStatus());
    const page0 = xamSecureCacheKey('posture_0_HIGH', '111');
    applySyncStamps(syncStatus({ POSTURE: '2026-09-07T09:00:00Z' }));
    expect(xamSecureCacheKey('posture_0_HIGH', '111')).not.toBe(page0);
  });

  /**
   * Live and mixed-source sections have no stamp that describes them, so they stay on the timer.
   * The network graph is the one worth pinning: it sits under inventory_* but is built from live
   * vendor calls, so it must not pick up the INVENTORY stamp.
   */
  it('leaves live and mixed sections on the timer', () => {
    applySyncStamps(syncStatus());
    ['inventory_network', 'components', 'workflows', 'compliance', 'stories', 'code_scans']
      .forEach((section) => {
        expect(xamSecureCacheKey(section, '111').endsWith('|t'), section).toBe(true);
      });
    // Its DB-backed siblings do get stamped.
    expect(xamSecureCacheKey('inventory_risk', '111').endsWith('|t')).toBe(false);
    expect(xamSecureCacheKey('inventory_0_compute', '111').endsWith('|t')).toBe(false);
  });
});
