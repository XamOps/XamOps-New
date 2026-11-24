import type { DatastoreSyncStatus } from '../../../../types/dspmScans';
import { formatListDate } from '../datastoreFormat';
import { formatRelativeLong } from '../saas/saasFormat';

/** What the info card shows for a sync job: the text, a spinner while it runs, and an optional hover title. */
export interface SyncView {
  text: string;
  spinner?: boolean;
  title?: string;
}

const SYNCING = new Set(['STARTING', 'IN_PROGRESS']);

/** True while a sync job runs, so the drawer polls its status (design 4.9). */
export function isSyncing(status: DatastoreSyncStatus | null): boolean {
  return SYNCING.has(status?.status ?? '');
}

/**
 * The info card's sync text (design 6.9), or null when nothing is shown: no sync job, a status the
 * console does not draw, or a finished sync without a time. `updatedAt` is epoch seconds.
 */
export function syncView(status: DatastoreSyncStatus | null, nowMs: number): SyncView | null {
  const code = status?.status ?? null;
  if (code === null) return null;
  if (SYNCING.has(code)) return { text: 'Syncing', spinner: true };
  if (code === 'SUCCESS') {
    const updatedAt = status?.updatedAt ?? null;
    if (updatedAt === null) return null;
    const title = `Finished ${formatListDate(updatedAt)}`;
    if (nowMs / 1000 - updatedAt < 60) return { text: 'Last sync just now', title };
    return { text: `Last sync ${formatRelativeLong(updatedAt, nowMs)}`, title };
  }
  if (code === 'FAILED') {
    return { text: 'Sync failed', title: 'The last sync did not finish, so the listing may be incomplete.' };
  }
  if (code === 'CANCELED') return { text: 'Sync canceled' };
  return null;
}
