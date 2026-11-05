import { describe, expect, it } from 'vitest';
import { formatAgo, formatBytes, formatCount, formatPct, isRangePreset, rangeMs } from './format';
import { lttb, prepareSeries } from './downsample';
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

describe('contrastRatio', () => {
  it('matches known values', () => {
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 0);
    expect(contrastRatio('#777777', '#777777')).toBeCloseTo(1, 5);
  });
});
