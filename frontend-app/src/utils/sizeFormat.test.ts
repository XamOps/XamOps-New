import { describe, it, expect } from 'vitest';
import { formatBytes, formatSizeRange } from './sizeFormat';

describe('sizeFormat', () => {
  describe('formatBytes', () => {
    it('returns "0 B" for 0', () => {
      expect(formatBytes(0)).toBe('0 B');
    });

    it('formats bytes correctly', () => {
      expect(formatBytes(500)).toBe('500 B');
    });

    it('formats KB correctly', () => {
      expect(formatBytes(1024)).toBe('1 KB');
      expect(formatBytes(1536)).toBe('1.5 KB');
    });

    it('formats MB correctly', () => {
      expect(formatBytes(1048576)).toBe('1 MB');
      expect(formatBytes(157286400)).toBe('150 MB');
    });

    it('formats GB correctly', () => {
      expect(formatBytes(1073741824)).toBe('1 GB');
    });

    it('returns "0 B" for negative values', () => {
      expect(formatBytes(-100)).toBe('0 B');
    });

    it('returns "0 B" for NaN', () => {
      expect(formatBytes(NaN)).toBe('0 B');
    });
  });

  describe('formatSizeRange', () => {
    it('formats a range with MB values', () => {
      // 120 MB = 125829120, 180 MB = 188743680
      const result = formatSizeRange(125829120, 188743680);
      expect(result).toContain('~');
      expect(result).toContain('MB');
      expect(result).toContain('–');
    });

    it('returns "—" for invalid inputs', () => {
      expect(formatSizeRange(-1, 100)).toBe('—');
      expect(formatSizeRange(NaN, 100)).toBe('—');
      expect(formatSizeRange(100, Infinity)).toBe('—');
    });

    it('handles zero range', () => {
      const result = formatSizeRange(0, 0);
      expect(result).toContain('0 B');
    });
  });
});
