/**
 * The Activity page's time filter against the server's rule (AutomationActivityController
 * .requireRange): a `from` more than 90 days before the server's own clock is a 400, so "Last 90
 * days" must stay inside it after the request's way there and a server clock a little ahead.
 */
import { describe, expect, it } from 'vitest';
import { ACTIVITY_MAX_RANGE_MS, RANGE_LIMIT_MARGIN_MS, TIME_RANGES, rangeFrom } from '../ActivityPage';

const MINUTE = 60_000;
const DAY = 24 * 60 * MINUTE;

/** The server's check: refused when `from` is more than 90 days before its now. */
const serverAccepts = (from: string, serverNow: number) => serverNow - Date.parse(from) <= ACTIVITY_MAX_RANGE_MS;

describe('rangeFrom: the server\'s `from` for each time filter', () => {
  const base = Date.parse('2026-10-20T09:30:00.000Z');
  // Every second of a minute, and the minute boundary itself, where rounding gives no room.
  const nows = [...Array.from({ length: 60 }, (_, s) => base + s * 1000), base + 59_999];

  it('keeps "Last 90 days" inside the server\'s 90 days, however late in the minute and with a server clock up to 4 minutes ahead', () => {
    const ninety = TIME_RANGES.find((r) => r.id === '90d')!;
    expect(ninety.label).toBe('Last 90 days');
    for (const now of nows) {
      const from = rangeFrom(ninety.ms, now)!;
      for (const ahead of [0, 250, 30_000, 4 * MINUTE]) {
        expect(serverAccepts(from, now + ahead), `now ${new Date(now).toISOString()}, server ${ahead} ms ahead`).toBe(true);
      }
      // It leaves out at most the oldest few minutes of the 90 days.
      expect(Date.parse(from) - (now - ninety.ms!)).toBeLessThanOrEqual(RANGE_LIMIT_MARGIN_MS + MINUTE);
    }
  });

  it('the rounding down it replaces was refused every time', () => {
    for (const now of nows) {
      const floored = new Date(Math.floor((now - 90 * DAY) / MINUTE) * MINUTE).toISOString();
      expect(serverAccepts(floored, now + 50)).toBe(false);
    }
  });

  it('fixes every other range to the minute, rounded up, and "All time" sends none', () => {
    const now = base + 42_123;
    for (const range of TIME_RANGES.filter((r) => r.ms !== null && r.id !== '90d')) {
      const from = Date.parse(rangeFrom(range.ms, now)!);
      expect(from % MINUTE).toBe(0);
      expect(from).toBeGreaterThanOrEqual(now - range.ms!);
      expect(from - (now - range.ms!)).toBeLessThan(MINUTE);
    }
    expect(rangeFrom(null, now)).toBeNull();
  });
});
