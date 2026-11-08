import React, { useCallback, useMemo, useState } from 'react';
import type { DatastoreAccessGraph, DatastoreAccessGroup, DatastoreRow } from '../../../../types/dspmDatastores';
import { MAX_SEARCH } from '../datastoreFilters';
import { SearchInput } from '../ui/SearchInput';
import AccessGraphCanvas from './AccessGraphCanvas';
import PermissionsPanel from './PermissionsPanel';
import { ZOOM_DEFAULT, ZOOM_MAX, ZOOM_MIN, accessGraphLayout, zoomStep } from './accessGraphLayout';

const TOOL = 'inline-flex items-center rounded-lg border border-[rgba(0,0,0,0.12)] px-2.5 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.65)] transition-colors hover:bg-[rgba(0,0,0,0.03)]';
const ZOOM = 'inline-flex h-7 w-7 items-center justify-center rounded-lg border border-[rgba(0,0,0,0.12)] text-sm font-semibold text-[rgba(0,0,0,0.65)] transition-colors hover:bg-[rgba(0,0,0,0.03)] disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent';

interface Props {
  graph: DatastoreAccessGraph;
  /** For the root box: its name when the graph has none, and its provider icon. */
  row: Pick<DatastoreRow, 'name' | 'source'>;
}

/**
 * The Access Graph tab: who can reach the datastore, drawn left to right as datastore → permission set
 * → principal type → principals (design 6.12).
 *
 * The search keeps the groups with a matching principal and lists only those principals, highlighted.
 * Expand all / Collapse all and each type box's link show or hide principals. Zoom runs from 20% to
 * 150% in 10% steps, starting at 70%. A Permissions box or type box opens the Permissions panel.
 */
const AccessGraphTab: React.FC<Props> = ({ graph, row }) => {
  const [query, setQuery] = useState('');
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set());
  const [zoom, setZoom] = useState(ZOOM_DEFAULT);
  // `seq` keys the panel, so every opening is a fresh panel that takes focus and registers its opener.
  const [panel, setPanel] = useState<{ group: DatastoreAccessGroup; opener: HTMLElement; seq: number } | null>(null);
  const closePanel = useCallback(() => setPanel(null), []);

  const layout = useMemo(() => accessGraphLayout(graph, expanded, query), [graph, expanded, query]);

  const toggle = useCallback((groupId: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(groupId)) next.delete(groupId); else next.add(groupId);
      return next;
    });
  }, []);
  const openPanel = useCallback((group: DatastoreAccessGroup, opener: HTMLElement) => {
    setPanel(prev => ({ group, opener, seq: (prev?.seq ?? 0) + 1 }));
  }, []);

  // The panel sits outside the spaced column: `space-y` margins would push an absolutely placed box down.
  return (
    <div className="relative">
      <div className="space-y-3">
        <div className="flex flex-wrap items-center gap-2">
          <SearchInput value={query} onChange={setQuery} placeholder="Search principals, roles…" maxLength={MAX_SEARCH} className="w-64" />
          <button type="button" onClick={() => setExpanded(new Set(graph.groups.map(group => group.id)))} className={TOOL}>
            Expand all
          </button>
          <button type="button" onClick={() => setExpanded(new Set())} className={TOOL}>Collapse all</button>
          <div className="ml-auto inline-flex items-center gap-1.5">
            <button
              type="button"
              aria-label="Zoom out"
              disabled={zoom <= ZOOM_MIN}
              onClick={() => setZoom(z => zoomStep(z, 'down'))}
              className={ZOOM}
            >
              −
            </button>
            <span className="w-10 text-center text-xs font-semibold tabular-nums text-[rgba(0,0,0,0.65)]">
              {Math.round(zoom * 100)}%
            </span>
            <button
              type="button"
              aria-label="Zoom in"
              disabled={zoom >= ZOOM_MAX}
              onClick={() => setZoom(z => zoomStep(z, 'up'))}
              className={ZOOM}
            >
              +
            </button>
          </div>
        </div>

        <AccessGraphCanvas
          layout={layout}
          zoom={zoom}
          rootName={graph.rootName ?? row.name ?? '-'}
          provider={row.source.provider}
          principalCount={graph.principalCount}
          onOpen={openPanel}
          onToggle={toggle}
        />

        <p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-[rgba(0,0,0,0.45)]">
          <span className="inline-flex items-center gap-1.5 font-semibold text-[rgba(0,0,0,0.55)]">
            <span aria-hidden="true" className="h-px w-5 bg-[rgba(0,0,0,0.35)]" />
            Grants
          </span>
          <span>Path: Datastore &gt; Permission set &gt; Role type &gt; Principals. Click a permission set or a principal for details.</span>
        </p>
      </div>

      {panel && (
        <PermissionsPanel key={panel.seq} group={panel.group} returnFocus={panel.opener} onClose={closePanel} />
      )}
    </div>
  );
};

export default AccessGraphTab;
