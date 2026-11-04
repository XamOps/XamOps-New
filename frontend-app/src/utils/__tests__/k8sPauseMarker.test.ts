import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  PAUSE_MARKER_MAX_AGE_MS,
  canPersistPauseMarker,
  clearPauseMarker,
  pauseMarkerKey,
  readPauseMarker,
  writePauseMarker,
} from '../k8sPauseMarker';

const SCOPE = { accountId: '111', clusterName: 'prod' };
const NOW = Date.parse('2026-05-10T12:00:00Z');
const iso = (ms: number) => new Date(ms).toISOString();

describe('k8sPauseMarker', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => vi.restoreAllMocks());

  it('reads back a fresh marker', () => {
    expect(writePauseMarker(SCOPE, { priorMode: 'scoped', pausedAt: iso(NOW - 1000) })).toBe(true);
    expect(readPauseMarker(SCOPE, NOW)).toEqual({ priorMode: 'scoped', pausedAt: iso(NOW - 1000) });
  });

  it('is keyed per account and cluster', () => {
    writePauseMarker(SCOPE, { priorMode: 'full', pausedAt: iso(NOW) });
    expect(readPauseMarker({ accountId: '222', clusterName: 'prod' }, NOW)).toBeNull();
    expect(readPauseMarker({ accountId: '111', clusterName: 'other' }, NOW)).toBeNull();
  });

  it('ignores and removes a marker older than 7 days, keeps one just under', () => {
    writePauseMarker(SCOPE, { priorMode: 'full', pausedAt: iso(NOW - 8 * 24 * 3600 * 1000) });
    expect(readPauseMarker(SCOPE, NOW)).toBeNull();
    expect(localStorage.getItem(pauseMarkerKey(SCOPE))).toBeNull();

    writePauseMarker(SCOPE, { priorMode: 'full', pausedAt: iso(NOW - (PAUSE_MARKER_MAX_AGE_MS - 3600 * 1000)) });
    expect(readPauseMarker(SCOPE, NOW)).not.toBeNull();
  });

  it.each([
    ['corrupt JSON', '{nope'],
    ['not an object', '"x"'],
    ['bad priorMode', JSON.stringify({ priorMode: 'disabled', pausedAt: iso(NOW) })],
    ['missing pausedAt', JSON.stringify({ priorMode: 'full' })],
    ['unparseable date', JSON.stringify({ priorMode: 'full', pausedAt: 'yesterday' })],
    ['future-dated', JSON.stringify({ priorMode: 'full', pausedAt: iso(NOW + 10 * 60 * 1000) })],
  ])('treats %s as no marker', (_label, raw) => {
    localStorage.setItem(pauseMarkerKey(SCOPE), raw);
    expect(readPauseMarker(SCOPE, NOW)).toBeNull();
  });

  it('tolerates small clock skew into the future', () => {
    writePauseMarker(SCOPE, { priorMode: 'full', pausedAt: iso(NOW + 60 * 1000) });
    expect(readPauseMarker(SCOPE, NOW)).not.toBeNull();
  });

  it('never throws when storage throws (private mode)', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('blocked'); });
    expect(readPauseMarker(SCOPE, NOW)).toBeNull();
    expect(writePauseMarker(SCOPE, { priorMode: 'full', pausedAt: iso(NOW) })).toBe(false);
    expect(clearPauseMarker(SCOPE)).toBe(false);
    expect(canPersistPauseMarker()).toBe(false);
  });

  it('clears a marker', () => {
    writePauseMarker(SCOPE, { priorMode: 'full', pausedAt: iso(NOW) });
    expect(clearPauseMarker(SCOPE)).toBe(true);
    expect(readPauseMarker(SCOPE, NOW)).toBeNull();
  });

  it('probes persistence without leaving anything behind', () => {
    expect(canPersistPauseMarker()).toBe(true);
    expect(localStorage.length).toBe(0);
  });
});
