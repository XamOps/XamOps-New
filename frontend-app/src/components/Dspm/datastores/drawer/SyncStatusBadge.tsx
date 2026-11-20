import React from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreSyncStatus } from '../../../../types/dspmScans';
import type { LazyResource } from '../../../../hooks/useDspmDatastoreDrawer';
import { syncView } from './syncStatus';

/**
 * The sync status beside the Scan Status chip (design 6.9). Nothing shows while the first answer loads,
 * after the request failed, or when `syncView` has no text. A poll's reload keeps the last answer on
 * screen, so the text does not blink every minute.
 */
export const SyncStatusBadge: React.FC<{ resource: LazyResource<DatastoreSyncStatus> }> = ({ resource }) => {
  if (resource.error) return null;
  const view = syncView(resource.data, Date.now());
  if (view === null) return null;
  return (
    <span
      data-sync-status=""
      title={view.title}
      className="ml-2 mt-1 inline-flex items-center gap-1 text-[11px] font-semibold text-[rgba(0,0,0,0.55)]"
    >
      {view.spinner && <Icon icon="mdi:loading" width={12} className="animate-spin" aria-hidden="true" />}
      {view.text}
    </span>
  );
};
