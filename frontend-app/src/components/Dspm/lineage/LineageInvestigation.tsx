import React, { useCallback, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import type { LineageCategory } from '../../../types/dspmLineage';
import type { DatastoreFilterState } from '../datastores/datastoreFilters';
import type { LineageEventView } from '../datastores/saas/lineageModel';
import { FullscreenFrame } from '../datastores/ui/FullscreenFrame';
import LineageEventDrawer from './LineageEventDrawer';
import LineageEscapeHost from './LineageEscapeHost';
import LineagePanel from './LineagePanel';
import {
  EMPTY_QUERY, clickNode, openLevel, setPage, setSearch, type LineageQuery, type OpenLevel, type RowLevel,
} from './lineageQuery';
import { NEW_INVESTIGATION, PAGE_SUBTITLE, PAGE_TITLE, PANEL_COPY } from './lineageText';
import type { TreeLevel } from './lineageTree';
import { useLineageData, type LineageFilters } from './useLineageData';

interface Props {
  query: LineageQuery;
  /** The chosen category, as step 0 listed it. */
  category: LineageCategory;
  /** Writes the next query to the URL. */
  onQuery: (query: LineageQuery) => void;
  /** A row answered `available: false` (the switch was turned off under the open page). */
  onUnavailable: () => void;
}

const NO_FILTERS: LineageFilters = { policy: {}, datastore: {} };

/**
 * Steps 1 to 4 (build 4 design 6.5): the header with "New Investigation", which goes back to step 0
 * (D1), and the panel with the tree, which full screen shows in a centred modal. The URL holds the
 * choices, pages and searches; the More filters live here and reset when a click clears their level.
 * "View full details →" in the event popover opens the event drawer, above full screen while it is open.
 */
const LineageInvestigation: React.FC<Props> = ({ query, category, onQuery, onUnavailable }) => {
  const [filters, setFilters] = useState<LineageFilters>(NO_FILTERS);
  const [fullscreen, setFullscreen] = useState(false);
  /** The event whose drawer is open, and the card that opened it. */
  const [openEvent, setOpenEvent] = useState<{ event: LineageEventView; anchor: HTMLElement } | null>(null);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const data = useLineageData(query, filters, onQuery, onUnavailable);

  const onNode = (level: TreeLevel, id: string) => {
    if (level === 'root') return;
    const t = clickNode(query, level, id);
    const reset = t.cleared.filter((l): l is 'policy' | 'datastore' => l === 'policy' || l === 'datastore');
    if (reset.some(l => Object.keys(filters[l]).length > 0)) {
      setFilters(prev => {
        const next = { ...prev };
        for (const l of reset) next[l] = {};
        return next;
      });
    }
    onQuery(t.query);
  };
  const onFilters = (level: 'policy' | 'datastore', next: DatastoreFilterState) => {
    setFilters(prev => ({ ...prev, [level]: next }));
    onQuery(setPage(query, level, 0));
  };
  const onPage = (level: RowLevel, direction: 1 | -1) => onQuery(setPage(query, level, query.page[level] + direction));
  const onSearch = (level: OpenLevel, text: string) => onQuery(setSearch(query, level, text));

  const exitFullscreen = useCallback(() => setFullscreen(false), []);
  const closeEvent = useCallback(() => setOpenEvent(null), []);
  const level = openLevel(query) ?? 'policy';

  return (
    <LineageEscapeHost>
      <header className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-lg font-bold text-[rgba(0,0,0,0.88)]">{PAGE_TITLE}</h1>
          <p className="mt-0.5 text-sm text-[rgba(0,0,0,0.5)]">{PAGE_SUBTITLE}</p>
        </div>
        <button
          type="button"
          onClick={() => onQuery(EMPTY_QUERY)}
          className="inline-flex items-center gap-1.5 rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-3 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.75)] transition-colors hover:bg-[rgba(0,0,0,0.03)]"
        >
          <Icon icon="mdi:plus-circle-outline" width={14} aria-hidden="true" />
          {NEW_INVESTIGATION}
        </button>
      </header>
      <div className="mt-4">
        <FullscreenFrame
          variant="modal"
          open={fullscreen}
          title={PANEL_COPY[level].heading}
          onExit={exitFullscreen}
          toggleRef={toggleRef}
        >
          <LineagePanel
            query={query}
            category={category}
            data={data}
            filters={filters}
            fullscreen={fullscreen}
            toggleRef={toggleRef}
            onToggleFullscreen={() => setFullscreen(true)}
            onNode={onNode}
            onPage={onPage}
            onSearch={onSearch}
            onFilters={onFilters}
            onViewDetails={(event, anchor) => setOpenEvent({ event, anchor })}
          />
        </FullscreenFrame>
      </div>
      {openEvent && (
        <LineageEventDrawer
          event={openEvent.event}
          returnFocus={openEvent.anchor}
          elevated={fullscreen}
          onClose={closeEvent}
        />
      )}
    </LineageEscapeHost>
  );
};

export default LineageInvestigation;
