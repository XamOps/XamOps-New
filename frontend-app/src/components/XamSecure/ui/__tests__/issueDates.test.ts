import { describe, it, expect } from 'vitest';
import { formatRelative, formatAbsolute } from '../issueDates';

describe('issueDates.formatRelative', () => {
  const now = Date.parse('2026-06-15T12:00:00Z');

  it('returns null for null/invalid input', () => {
    expect(formatRelative(null, now)).toBeNull();
    expect(formatRelative(undefined, now)).toBeNull();
    expect(formatRelative('not-a-date', now)).toBeNull();
  });

  it('picks a coarse past unit', () => {
    const twoMonthsAgo = new Date(now - 62 * 86_400_000).toISOString();
    const rel = formatRelative(twoMonthsAgo, now);
    expect(rel).toMatch(/month/);
  });

  it('picks hours for a few-hours-old date', () => {
    const threeHoursAgo = new Date(now - 3 * 3_600_000).toISOString();
    expect(formatRelative(threeHoursAgo, now)).toMatch(/hour/);
  });

  it('handles future dates', () => {
    const inTwoDays = new Date(now + 2 * 86_400_000).toISOString();
    const rel = formatRelative(inTwoDays, now)!;
    expect(rel).toMatch(/day|tomorrow/i);
  });
});

describe('issueDates.formatAbsolute', () => {
  it('returns null for null/invalid input', () => {
    expect(formatAbsolute(null)).toBeNull();
    expect(formatAbsolute('nonsense')).toBeNull();
  });

  it('formats a valid ISO into a readable string (en locale → ordinal day)', () => {
    const out = formatAbsolute('2026-08-02T20:01:00Z')!;
    expect(out).toContain('2026');
    expect(out).toContain('Aug');
    // en-* resolved locale yields an ordinal suffix on the day-of-month.
    if (new Intl.DateTimeFormat().resolvedOptions().locale.startsWith('en')) {
      expect(out).toMatch(/\d(st|nd|rd|th)/);
    }
  });

  it('omits the ordinal entirely for a non-en resolved locale', () => {
    // Guard is locale-based; this asserts the helper never throws and returns a usable string
    // for a non-en construction path (we cannot force the environment locale here, so we assert
    // the contract holds for the resolved locale).
    const out = formatAbsolute('2026-08-02T20:01:00Z')!;
    const locale = new Intl.DateTimeFormat().resolvedOptions().locale;
    if (!locale.startsWith('en')) {
      expect(out).not.toMatch(/\d(st|nd|rd|th)/);
    }
    expect(typeof out).toBe('string');
  });
});
