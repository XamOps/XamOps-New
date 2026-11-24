import { describe, expect, it } from 'vitest';
import { formatBytes, formatConsoleDate } from '../dspmFormat';

// The Datastores page and drawer reuse these two helpers unchanged; these cases pin the console's format.

describe('formatBytes', () => {
  it('uses base 1024 with up to two decimals', () => {
    expect(formatBytes(15_454_389_580)).toBe('14.39 GB');
  });

  it('spells out "0 Bytes"', () => {
    expect(formatBytes(0)).toBe('0 Bytes');
  });
});

describe('formatConsoleDate', () => {
  it('prints the Scan History date with a zero-padded hour', () => {
    // Built from local date parts, so the expectation holds in any time zone.
    expect(formatConsoleDate(new Date(2026, 8, 11, 15, 50).getTime() / 1000)).toBe('11 Sep 2026, 03:50 PM');
  });
});
