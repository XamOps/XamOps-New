import React, { useCallback, useState } from 'react';
import { Icon } from '@iconify/react';
import type { DspmError } from '../../../../types/dspm';
import type { LazyResource } from '../../../../hooks/useDspmDatastoreDrawer';
import { datastoreErrorCopy } from '../datastoreErrors';
import { EmptyState } from '../ui/EmptyState';

/*
 * Pieces the drawer's tabs share: their loading and error states, the expand chevron of nested tables,
 * the table header style, the empty-table row and the hidden-columns state behind a "Columns N" menu.
 */

/** Header cell style of the drawer's tables. */
export const TH = 'px-3 py-2.5 text-left text-[11px] font-semibold text-[rgba(0,0,0,0.5)]';

/** The console's text for a datastore that has nothing to show yet (not scanned, or no findings). */
export const NOT_SCANNED_TEXT = 'Scan this datastore to see the results here.';

export const LoadingNote: React.FC = () => (
  <p className="py-10 text-center text-xs text-[rgba(0,0,0,0.4)]">Loading…</p>
);

/** The error copy for the code, with Retry when retrying can help (design 6.16). */
export const ErrorNote: React.FC<{ error: DspmError; onRetry?: () => void }> = ({ error, onRetry }) => {
  const copy = datastoreErrorCopy(error);
  const retry = (e: React.MouseEvent<HTMLButtonElement>) => {
    // This note unmounts as soon as the retry starts; keep focus in the drawer rather than let it fall
    // to the page body (design 6.7).
    e.currentTarget.closest<HTMLElement>('[role="dialog"]')?.focus();
    onRetry?.();
  };
  return (
    <div role="alert" className="flex items-start gap-2 rounded-lg border border-rose-100 bg-rose-50 p-3 text-xs text-rose-700">
      <Icon icon="mdi:alert-circle-outline" width={14} className="mt-0.5 flex-shrink-0" />
      <span className="flex-1">{copy.text}</span>
      {copy.retry && onRetry && (
        <button type="button" onClick={retry} className="font-semibold underline">Retry</button>
      )}
    </div>
  );
};

/** A tab body over one drawer resource: "Loading…", the error with Retry, or the content. */
export function ResourceView<T>({ resource, children }: {
  resource: LazyResource<T>;
  children: (data: T) => React.ReactNode;
}) {
  if (resource.error) return <ErrorNote error={resource.error} onRetry={resource.reload} />;
  if (resource.data === null) return <LoadingNote />;
  return <>{children(resource.data)}</>;
}

/** The chevron that expands a row of a nested table. */
export const ExpandButton: React.FC<{ expanded: boolean; name: string; onToggle: () => void }> = ({
  expanded, name, onToggle,
}) => (
  <button
    type="button"
    aria-expanded={expanded}
    aria-label={`${expanded ? 'Collapse' : 'Expand'} ${name}`}
    onClick={e => {
      e.stopPropagation();   // the row toggles on click as well
      onToggle();
    }}
    className="inline-flex flex-shrink-0 items-center rounded p-0.5 text-[rgba(0,0,0,0.4)] hover:bg-[rgba(0,0,0,0.05)] hover:text-[rgba(0,0,0,0.7)]"
  >
    <Icon icon={expanded ? 'mdi:chevron-down' : 'mdi:chevron-right'} width={14} />
  </button>
);

/**
 * The body row of an empty table. The header row above it stays (design 6.8). With a search, the table
 * says nothing matches instead of asking for a scan.
 */
export const EmptyTableRow: React.FC<{ colSpan: number; searching: boolean }> = ({ colSpan, searching }) => (
  <tr>
    <td colSpan={colSpan}>
      {searching
        ? <EmptyState title="No matches" body="Try a different search." />
        : <EmptyState title={NOT_SCANNED_TEXT} />}
    </td>
  </tr>
);

/** The columns a table's "Columns N" menu has hidden. Local state, so they reset when the view unmounts. */
export function useHiddenColumns<Id extends string>() {
  const [hidden, setHidden] = useState<ReadonlySet<Id>>(() => new Set<Id>());
  const toggle = useCallback((id: Id) => {
    setHidden(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }, []);
  return [hidden, toggle] as const;
}
