import React from 'react';
import { Icon } from '@iconify/react';
import type { EndpointFileActivity, EndpointFileActivityEntry } from '../../../../types/dspmFiles';
import type { LazyResource } from '../../../../hooks/useDspmDatastoreDrawer';
import { formatConsoleDate } from '../datastoreFormat';
import { ResourceView } from '../drawer/TabParts';
import { GreyTag } from '../saas/SaasParts';
import { EmptyState } from '../ui/EmptyState';

/** The icon of each MIP action; any other action draws the tag. */
const ACTION_ICONS: Readonly<Record<string, string>> = {
  TAG_APPLIED: 'mdi:tag-outline',
  TAG_CHANGED: 'mdi:swap-horizontal',
  TAG_REMOVED: 'mdi:minus-circle-outline',
  TAG_SKIPPED: 'mdi:cancel',
  USER_OVERRIDE: 'mdi:account-outline',
};
const DEFAULT_ICON = 'mdi:tag-outline';

/** "<previous> -> <selected>" for a change, the previous tag for a removal, else the selected tag. */
function tagText(entry: EndpointFileActivityEntry): string | null {
  switch (entry.action) {
    case 'TAG_CHANGED':
      return entry.previousTag === null && entry.selectedTag === null
        ? null
        : `${entry.previousTag ?? '-'} -> ${entry.selectedTag ?? '-'}`;
    case 'TAG_REMOVED':
      return entry.previousTag;
    default:
      return entry.selectedTag;
  }
}

/** Where the tag was written, or why it was not. */
function noteText(entry: EndpointFileActivityEntry): string | null {
  if (entry.action === 'TAG_SKIPPED') return 'Skipped: the file already has a MIP label';
  if (entry.mipLabelWritten) return 'Written to Keywords and the MIP label';
  if (entry.keywordsWritten) return 'Written to Keywords only';
  return null;
}

/**
 * The Endpoint file's File Activity tab (plan C18): the file's MIP tag audit log in API order, each entry
 * with its action's icon and label, its tags, where the tag was written, and who did it when.
 */
const EndpointFileActivityTab: React.FC<{ activity: LazyResource<EndpointFileActivity> }> = ({ activity }) => (
  <ResourceView resource={activity} subject="file">
    {data => data.entries.length === 0 ? (
      <div className="rounded-xl border border-[rgba(0,0,0,0.08)]">
        <EmptyState title="No file activity yet" body="Tag changes for this file will appear here." />
      </div>
    ) : (
      <ol aria-label="File activity" className="divide-y divide-[rgba(0,0,0,0.06)] rounded-xl border border-[rgba(0,0,0,0.08)]">
        {data.entries.map((entry, index) => {
          const tags = tagText(entry);
          const note = noteText(entry);
          return (
            <li key={index} className="flex items-start gap-3 px-4 py-3 text-xs">
              <span className="mt-0.5 flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full bg-[rgba(0,0,0,0.05)] text-[rgba(0,0,0,0.55)]">
                <Icon icon={(entry.action !== null ? ACTION_ICONS[entry.action] : undefined) ?? DEFAULT_ICON} width={14} />
              </span>
              <div className="min-w-0 flex-1 space-y-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-semibold text-[rgba(0,0,0,0.85)]">{entry.actionLabel ?? entry.action ?? '-'}</span>
                  {tags !== null && <GreyTag>{tags}</GreyTag>}
                </div>
                {note !== null && <p className="text-[11px] text-[rgba(0,0,0,0.6)]">{note}</p>}
                <p className="text-[11px] text-[rgba(0,0,0,0.45)]">
                  {entry.performedBy ?? '-'} - {entry.occurredAt === null ? '-' : formatConsoleDate(entry.occurredAt)}
                </p>
              </div>
            </li>
          );
        })}
      </ol>
    )}
  </ResourceView>
);

export default EndpointFileActivityTab;
