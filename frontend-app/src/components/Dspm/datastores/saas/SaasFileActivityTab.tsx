import React from 'react';
import type { SaasFileActivity } from '../../../../types/dspmDrawers';
import type { LazyResource } from '../../../../hooks/useDspmDatastoreDrawer';
import { formatConsoleDate } from '../datastoreFormat';
import { EmptyState } from '../ui/EmptyState';
import { ResourceView } from '../drawer/TabParts';
import { GreyTag } from './SaasParts';

/**
 * The file's File Activity tab (FR-SFILE-8): the remediation log, newest first, as a plain list (DA6: the
 * console's layout of a non-empty log is unseen).
 */
const SaasFileActivityTab: React.FC<{ activity: LazyResource<SaasFileActivity> }> = ({ activity }) => (
  <ResourceView resource={activity} subject="file">
    {data => data.entries.length === 0 ? (
      <div className="rounded-xl border border-[rgba(0,0,0,0.08)]">
        <EmptyState title="No file activity yet" body="Actions taken on this file will appear here." />
      </div>
    ) : (
      <ol aria-label="File activity" className="divide-y divide-[rgba(0,0,0,0.06)] rounded-xl border border-[rgba(0,0,0,0.08)]">
        {data.entries.map((entry, index) => (
          <li key={index} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3 text-xs">
            <span className="font-semibold text-[rgba(0,0,0,0.85)]">{entry.title ?? '-'}</span>
            {entry.status && <GreyTag>{entry.status}</GreyTag>}
            <span className="text-[rgba(0,0,0,0.6)]">{entry.actor ?? '-'}</span>
            <span className="ml-auto text-[11px] text-[rgba(0,0,0,0.45)]">
              {entry.occurredAt === null ? '-' : formatConsoleDate(entry.occurredAt)}
            </span>
          </li>
        ))}
      </ol>
    )}
  </ResourceView>
);

export default SaasFileActivityTab;
