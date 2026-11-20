import { describe, expect, it } from 'vitest';
import { secretExpiry, secretExpiryText } from '../assistFormat';

const NOW_MS = Date.UTC(2026, 10, 19, 12, 0, 0);
const NOW = NOW_MS / 1000;
const DAY = 86_400;

describe('secretExpiry', () => {
  it('has no tag without an expiry', () => {
    expect(secretExpiry(null, NOW_MS)).toBeNull();
  });

  it('reads exactly now, and any earlier time, as expired', () => {
    expect(secretExpiry(NOW, NOW_MS)).toEqual({ kind: 'expired' });
    expect(secretExpiry(NOW - 1, NOW_MS)).toEqual({ kind: 'expired' });
    expect(secretExpiry(NOW - 400 * DAY, NOW_MS)).toEqual({ kind: 'expired' });
  });

  it('rounds the days left up', () => {
    expect(secretExpiry(NOW + 1, NOW_MS)).toEqual({ kind: 'soon', days: 1 });
    expect(secretExpiry(NOW + DAY, NOW_MS)).toEqual({ kind: 'soon', days: 1 });
    expect(secretExpiry(NOW + DAY + 1, NOW_MS)).toEqual({ kind: 'soon', days: 2 });
    expect(secretExpiry(NOW + 10 * DAY, NOW_MS)).toEqual({ kind: 'soon', days: 10 });
  });

  it('tags exactly 30 days ahead, and not 30 days and 1 second', () => {
    expect(secretExpiry(NOW + 30 * DAY, NOW_MS)).toEqual({ kind: 'soon', days: 30 });
    expect(secretExpiry(NOW + 30 * DAY + 1, NOW_MS)).toBeNull();
    expect(secretExpiry(NOW + 200 * DAY, NOW_MS)).toBeNull();
  });
});

describe('secretExpiryText', () => {
  it('words the tags', () => {
    expect(secretExpiryText({ kind: 'expired' })).toBe('Expired');
    expect(secretExpiryText({ kind: 'soon', days: 10 })).toBe('Expires in 10 days');
    expect(secretExpiryText({ kind: 'soon', days: 1 })).toBe('Expires in 1 day');
  });
});
