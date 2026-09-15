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
   * The list loaders on Overview, Threats and Vulnerabilities are cached now, so their sections
   * have to resolve to a stamp rather than falling through to the timer.
   */
  it('stamps the tab-level list sections against the right domain', () => {
    applySyncStamps(syncStatus());
    const before = {
      threats: xamSecureCacheKey('threats_0_', '111'),
      threatGroups: xamSecureCacheKey('threat_groups_threat', '111'),
      byCve: xamSecureCacheKey('by_cve_0__runtime', '111'),
      vulnGroups: xamSecureCacheKey('vuln_groups_image_0', '111'),
    };
    Object.entries(before).forEach(([name, key]) => {
      expect(key.endsWith('|t'), `${name} must be stamped, not timer-keyed`).toBe(false);
    });

    // Detections move on their own 60s poll and must not disturb the vulnerability lists.
    applySyncStamps(syncStatus({ DETECTIONS: '2026-09-07T00:01:00Z' }));
    expect(xamSecureCacheKey('threats_0_', '111')).not.toBe(before.threats);
    expect(xamSecureCacheKey('threat_groups_threat', '111')).not.toBe(before.threatGroups);
    expect(xamSecureCacheKey('by_cve_0__runtime', '111')).toBe(before.byCve);
    expect(xamSecureCacheKey('vuln_groups_image_0', '111')).toBe(before.vulnGroups);

    // And the reverse.
    applySyncStamps(syncStatus({ VULNERABILITIES: '2026-09-07T07:00:00Z' }));
    expect(xamSecureCacheKey('by_cve_0__runtime', '111')).not.toBe(before.byCve);
    expect(xamSecureCacheKey('vuln_groups_image_0', '111')).not.toBe(before.vulnGroups);
  });

  /** threats_ and threat_overview_/threat_groups_ share a prefix run; keep them distinct. */
  it('does not confuse the threat section prefixes', () => {
    applySyncStamps(syncStatus());
    const keys = ['threats_0_', 'threat_overview_7', 'threat_groups_threat']
      .map((s) => xamSecureCacheKey(s, '111'));
    expect(new Set(keys).size).toBe(3);
    keys.forEach((k) => expect(k.endsWith('|t')).toBe(false));
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
