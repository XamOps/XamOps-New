import { describe, it, expect } from 'vitest';
import { severityTotal, SEVERITY_ORDER } from '../../types/xamsecure';
import { xamSecureCacheKey } from '../useXamSecureData';

describe('xamsecure helpers', () => {
  it('severityTotal sums all severities', () => {
    expect(severityTotal({ CRITICAL: 2, HIGH: 3 })).toBe(5);
    expect(severityTotal(undefined)).toBe(0);
  });

  it('severity order is fixed CRITICAL-first', () => {
    expect(SEVERITY_ORDER[0]).toBe('CRITICAL');
  });

  it('cache key is scoped per account', () => {
    expect(xamSecureCacheKey('summary', '111')).toBe('xamsecure_summary_111');
    expect(xamSecureCacheKey('summary', null)).toBe('xamsecure_summary_all');
  });
});
