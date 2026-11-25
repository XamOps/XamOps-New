import { describe, expect, it } from 'vitest';
import { formatAgo, formatBytes, formatCount, formatPct, isRangePreset, rangeMs } from './format';
import { lttb, noDataRanges, prepareSegments, prepareSeries, segmentSeries } from './downsample';
import { linearPath } from '../../components/XamPulse/charts/TimeSeriesChart';
import { contrastRatio } from './contrast';

describe('format', () => {
  it('formats bytes, counts and percentages', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(1536)).toBe('1.50 KB');
    expect(formatBytes(null)).toBe('\u2014');
    expect(formatCount(12_500)).toBe('13k');
    expect(formatPct(null)).toBe('\u2014');
    expect(formatPct(41.6)).toBe('42%');
  });
  it('formats relative time with an injectable clock', () => {
    const now = Date.parse('2026-01-01T12:00:00Z');
    expect(formatAgo('2026-01-01T11:55:00Z', now)).toBe('5m ago');
    expect(formatAgo(null, now)).toBe('never');
  });
  it('knows the range presets', () => {
    expect(isRangePreset('1h')).toBe(true);
    expect(isRangePreset('2h')).toBe(false);
    expect(rangeMs('24h')).toBe(86_400_000);
  });
});

describe('downsample', () => {
  it('keeps endpoints and respects the threshold', () => {
    const data: [number, number][] = Array.from({ length: 1000 }, (_, i) => [i, Math.sin(i / 20)]);
    const out = lttb(data, 100);
    expect(out).toHaveLength(100);
    expect(out[0]).toEqual(data[0]);
    expect(out[99]).toEqual(data[999]);
  });
  it('drops null points and returns short series unchanged', () => {
    expect(prepareSeries([[1, 1], [2, null], [3, 3]], 50)).toEqual([[1, 1], [3, 3]]);
  });
});

describe('gaps', () => {
  const minute = 60_000;
  // One point a minute for 10 minutes, then 20 minutes with nothing (agent offline), then 5 more minutes.
  const series: [number, number | null][] = [
    ...Array.from({ length: 10 }, (_, i) => [i * minute, 1] as [number, number]),
    ...Array.from({ length: 5 }, (_, i) => [(30 + i) * minute, 2] as [number, number]),
  ];

  it('never joins two runs of data across a gap', () => {
    const { segments, gapMs } = segmentSeries(series);
    expect(gapMs).toBe(2.5 * minute);
    expect(segments).toHaveLength(2);
    expect(segments[0][segments[0].length - 1][0]).toBe(9 * minute);
    expect(segments[1][0][0]).toBe(30 * minute);
  });

  it('breaks at an explicit null and tolerates one missing sample', () => {
    expect(segmentSeries([[0, 1], [minute, null], [2 * minute, 1], [3 * minute, 1]]).segments).toHaveLength(2);
    // A single missing minute (2 min spacing) stays one run: 2 min <= 2.5 x median spacing.
    expect(segmentSeries([[0, 1], [minute, 1], [3 * minute, 1], [4 * minute, 1]]).segments).toHaveLength(1);
  });

  it('marks the offline stretch, and the end of the range after the last sample, as no data', () => {
    const s = segmentSeries(series);
    const ranges = noDataRanges([s], 0, 40 * minute);
    // Each point covers half the gap limit (75 s) either side: 9:00+75s .. 30:00-75s, then 34:00+75s .. 40:00.
    expect(ranges).toEqual([[9 * minute + 75_000, 30 * minute - 75_000], [34 * minute + 75_000, 40 * minute]]);
  });

  it('reports no gap when the data covers the whole range', () => {
    const s = segmentSeries(Array.from({ length: 11 }, (_, i) => [i * minute, 1] as [number, number]));
    expect(noDataRanges([s], 0, 10 * minute)).toEqual([]);
  });

  it('keeps every run when downsampling', () => {
    const long: [number, number][] = Array.from({ length: 2000 }, (_, i) => [i < 1000 ? i * minute : (i + 500) * minute, i]);
    const out = prepareSegments(long, 100);
    expect(out.segments).toHaveLength(2);
    expect(out.segments.reduce((n, g) => n + g.length, 0)).toBeLessThanOrEqual(102);
  });

  it('draws straight segments, never curves that overshoot the samples', () => {
    expect(linearPath([{ x: 0, y: 10 }, { x: 5, y: 0 }, { x: 10, y: 10 }])).toBe('M0.0,10.0 L5.0,0.0 L10.0,10.0');
  });
});

describe('contrastRatio', () => {
  it('matches known values', () => {
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 0);
    expect(contrastRatio('#777777', '#777777')).toBeCloseTo(1, 5);
  });
});
