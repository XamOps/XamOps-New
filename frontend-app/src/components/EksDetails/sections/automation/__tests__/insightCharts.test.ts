/**
 * coverageInfo's words when Prometheus has no sample at all: a window that ends now may still fill
 * in ("yet"); a window that ended long ago (the impact chart around a change older than Prometheus'
 * history, as on xamops-test-1, which keeps 3 days) won't, and says why instead.
 */
import { describe, expect, it } from 'vitest';
import { coverageInfo } from '../insightCharts';

const NOW = Date.UTC(2026, 9, 19, 12, 0, 0);
const HOUR = 3_600_000;
const label = (ms: number) => new Date(ms).toISOString().slice(11, 16);
const empty = { firstSampleAt: null, covered: 0 };

describe('coverageInfo with no samples', () => {
  it('says "yet" for a window that ends now', () => {
    const info = coverageInfo({ start: NOW - 72 * HOUR, end: NOW, stepSeconds: 1800, coverage: empty }, label, NOW);
    expect(info.state).toEqual({ kind: 'unavailable', reason: 'Prometheus in your cluster has no samples for this window yet.' });
  });

  it('names the window and the likely reason for one that ended days ago', () => {
    const start = NOW - 5 * 24 * HOUR;
    const info = coverageInfo({ start, end: start + 6 * HOUR, stepSeconds: 60, coverage: empty }, label, NOW);
    expect(info.state).toEqual({
      kind: 'unavailable',
      reason: `Prometheus in your cluster has no samples from ${label(start)} to ${label(start + 6 * HOUR)}: its history may not reach back that far.`,
    });
    expect(info.noDataBefore).toBeNull();
  });
});
