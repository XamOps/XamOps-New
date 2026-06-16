import { describe, it, expect } from 'vitest';
import { secondsToHHMMSS, hhmmssToSeconds, formatTimeInput } from './timeFormat';

describe('timeFormat', () => {
  describe('secondsToHHMMSS', () => {
    it('converts 0 to 00:00:00', () => {
      expect(secondsToHHMMSS(0)).toBe('00:00:00');
    });

    it('converts 61 to 00:01:01', () => {
      expect(secondsToHHMMSS(61)).toBe('00:01:01');
    });

    it('converts 3661 to 01:01:01', () => {
      expect(secondsToHHMMSS(3661)).toBe('01:01:01');
    });

    it('converts 7200 to 02:00:00', () => {
      expect(secondsToHHMMSS(7200)).toBe('02:00:00');
    });

    it('handles negative values gracefully', () => {
      expect(secondsToHHMMSS(-1)).toBe('00:00:00');
    });

    it('handles NaN gracefully', () => {
      expect(secondsToHHMMSS(NaN)).toBe('00:00:00');
    });

    it('handles Infinity gracefully', () => {
      expect(secondsToHHMMSS(Infinity)).toBe('00:00:00');
    });

    it('rounds floating point seconds', () => {
      expect(secondsToHHMMSS(61.7)).toBe('00:01:02');
    });
  });

  describe('hhmmssToSeconds', () => {
    it('parses "01:01:01" to 3661', () => {
      expect(hhmmssToSeconds('01:01:01')).toBe(3661);
    });

    it('parses "00:00:00" to 0', () => {
      expect(hhmmssToSeconds('00:00:00')).toBe(0);
    });

    it('parses "02:00:00" to 7200', () => {
      expect(hhmmssToSeconds('02:00:00')).toBe(7200);
    });

    it('parses MM:SS format "05:30" to 330', () => {
      expect(hhmmssToSeconds('05:30')).toBe(330);
    });

    it('parses plain seconds string "120" to 120', () => {
      expect(hhmmssToSeconds('120')).toBe(120);
    });

    it('parses decimal seconds "90.5" to 91 (rounded)', () => {
      expect(hhmmssToSeconds('90.5')).toBe(91);
    });

    it('returns 0 for empty string', () => {
      expect(hhmmssToSeconds('')).toBe(0);
    });

    it('returns 0 for null/undefined-like input', () => {
      expect(hhmmssToSeconds(null as any)).toBe(0);
      expect(hhmmssToSeconds(undefined as any)).toBe(0);
    });
  });

  describe('round-trip: secondsToHHMMSS → hhmmssToSeconds', () => {
    it('round-trips 0', () => {
      expect(hhmmssToSeconds(secondsToHHMMSS(0))).toBe(0);
    });

    it('round-trips 3661', () => {
      expect(hhmmssToSeconds(secondsToHHMMSS(3661))).toBe(3661);
    });

    it('round-trips 86399 (23:59:59)', () => {
      expect(hhmmssToSeconds(secondsToHHMMSS(86399))).toBe(86399);
    });
  });

  describe('formatTimeInput', () => {
    it('returns empty string for empty input', () => {
      expect(formatTimeInput('')).toBe('');
    });

    it('strips non-numeric/colon characters', () => {
      expect(formatTimeInput('1a2b3')).toBe('1:23');
    });

    it('preserves valid colon-separated format', () => {
      expect(formatTimeInput('01:30:00')).toBe('01:30:00');
    });

    it('auto-formats 4 digits into MM:SS', () => {
      expect(formatTimeInput('1234')).toBe('12:34');
    });

    it('auto-formats 6 digits into HH:MM:SS', () => {
      expect(formatTimeInput('013000')).toBe('01:30:00');
    });
  });
});
