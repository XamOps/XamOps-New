import { describe, expect, it } from 'vitest';
import { isSyncing, syncView } from '../syncStatus';
import { formatListDate } from '../../datastoreFormat';

const NOW_MS = Date.UTC(2026, 10, 19, 12, 0, 0);
const NOW = NOW_MS / 1000;
const DAY = 86_400;

describe('syncView (AC17)', () => {
  it.each(['STARTING', 'IN_PROGRESS'])('shows "Syncing" with a spinner for %s', status => {
    expect(syncView({ status, updatedAt: NOW - 30 }, NOW_MS)).toEqual({ text: 'Syncing', spinner: true });
    expect(isSyncing({ status, updatedAt: null })).toBe(true);
  });

  it('reads "Last sync just now" for a sync that finished under 60 seconds ago', () => {
    expect(syncView({ status: 'SUCCESS', updatedAt: NOW - 59 }, NOW_MS)).toEqual({
      text: 'Last sync just now', title: `Finished ${formatListDate(NOW - 59)}`,
    });
  });

  it('reads the long relative age for an older sync, with the absolute time as its title', () => {
    expect(syncView({ status: 'SUCCESS', updatedAt: NOW - 60 }, NOW_MS)?.text).toBe('Last sync a minute ago');
    expect(syncView({ status: 'SUCCESS', updatedAt: NOW - 3 * 3600 }, NOW_MS)?.text).toBe('Last sync 3 hours ago');
    const view = syncView({ status: 'SUCCESS', updatedAt: NOW - 61 * DAY }, NOW_MS);
    expect(view).toEqual({ text: 'Last sync 2 months ago', title: `Finished ${formatListDate(NOW - 61 * DAY)}` });
  });

  it('hides a finished sync without a time', () => {
    expect(syncView({ status: 'SUCCESS', updatedAt: null }, NOW_MS)).toBeNull();
  });

  it('explains a failed sync in its title', () => {
    expect(syncView({ status: 'FAILED', updatedAt: NOW - DAY }, NOW_MS)).toEqual({
      text: 'Sync failed', title: 'The last sync did not finish, so the listing may be incomplete.',
    });
  });

  it('reads "Sync canceled" for a canceled sync', () => {
    expect(syncView({ status: 'CANCELED', updatedAt: NOW }, NOW_MS)).toEqual({ text: 'Sync canceled' });
  });

  it.each([
    ['no answer', null],
    ['no sync job', { status: null, updatedAt: null }],
    ['an unknown status', { status: 'QUEUED', updatedAt: NOW }],
  ])('shows nothing for %s', (_case, status) => {
    expect(syncView(status, NOW_MS)).toBeNull();
    expect(isSyncing(status)).toBe(false);
  });

  it('polls only while a sync runs', () => {
    for (const status of ['SUCCESS', 'FAILED', 'CANCELED']) {
      expect(isSyncing({ status, updatedAt: NOW })).toBe(false);
    }
  });
});
