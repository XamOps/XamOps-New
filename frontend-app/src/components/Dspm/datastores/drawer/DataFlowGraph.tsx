import React, { Suspense, useCallback, useEffect, useRef, useState, type RefObject } from 'react';
import type { DspmError } from '../../../../types/dspm';
import type { DataFlowErd } from '../../../../types/dspmDrawers';
import type { DatastoreDrawerApi } from '../../../../hooks/useDspmDatastoreDrawer';
import { toDspmError } from '../../../../hooks/useDspmDatastores';
import { MAX_SEARCH } from '../datastoreFilters';
import { ColumnsMenu } from '../ui/ColumnsMenu';
import { ConsoleOnlyButton } from '../ui/ConsoleOnly';
import { EmptyState } from '../ui/EmptyState';
import { FullscreenFrame } from '../ui/FullscreenFrame';
import { SearchInput } from '../ui/SearchInput';
import { SegmentedToggle } from '../ui/SegmentedToggle';
import type { TableParent } from './AssetHierarchy';
import {
  COLUMNS, DataFlowBreadcrumb, MODES, searchTerm, useHeadingFocus, type OpenTable, type TableColumnId, type TablesMode,
} from './DataFlowTables';
import { ERD_DASH, ERD_EDGE, KeyBadge, SensitiveDot } from './erd/ErdParts';
import TablePanel from './erd/TablePanel';
import { ErrorNote, LoadingNote } from './TabParts';

const ErdCanvas = React.lazy(() => import('./erd/ErdCanvas'));

const FOOTER = 'Path: Instance > Database > Schema > Table > Columns. Click a table to open its details. '
  + 'Hover a table to highlight its FK relationships.';

/** The legend under the canvas, in the console's order and words. */
const ErdLegend: React.FC = () => (
  <div className="flex flex-wrap items-center justify-end gap-x-4 gap-y-1 text-[11px] text-[rgba(0,0,0,0.6)]">
    <span className="inline-flex items-center gap-1.5"><KeyBadge kind="PK" />{' '}Primary key</span>
    <span className="inline-flex items-center gap-1.5"><KeyBadge kind="FK" />{' '}Foreign key</span>
    <span className="inline-flex items-center gap-1.5"><SensitiveDot />Sensitives</span>
    <span className="inline-flex items-center gap-1.5">
      <svg aria-hidden="true" width="28" height="8" className="flex-shrink-0">
        <line x1="0" y1="4" x2="22" y2="4" stroke={ERD_EDGE} strokeWidth="1.5" />
        <path d="M 22 0 L 28 4 L 22 8 z" fill={ERD_EDGE} />
      </svg>
      FK relationship
    </span>
    <span className="inline-flex items-center gap-1.5">
      <svg aria-hidden="true" width="28" height="8" className="flex-shrink-0">
        <line x1="0" y1="4" x2="28" y2="4" stroke={ERD_EDGE} strokeWidth="1.5" strokeDasharray={ERD_DASH} />
      </svg>
      Table not on this diagram
    </span>
  </div>
);

/** The open table panel: which table, the card that opened it, and a sequence number that keys the panel. */
interface PanelTable {
  table: OpenTable;
  opener: HTMLElement;
  seq: number;
}

interface Props {
  api: DatastoreDrawerApi;
  rootName: string;
  parent: TableParent;
  /** The toolbar's raw search text, shared with Table View: 3+ characters dim the cards that do not match. */
  search: string;
  onSearchChange: (text: string) => void;
  /** Table View's hidden columns: the chooser is shown here too but changes Table View only (A31). */
  hidden: ReadonlySet<TableColumnId>;
  onToggleColumn: (id: TableColumnId) => void;
  onModeChange: (mode: TablesMode) => void;
  /** The diagrams already loaded, by `parent.key`, owned by `DataFlowTab`. */
  erdCache: RefObject<Map<string, DataFlowErd>>;
}

/**
 * Graph View (design 6.8, FR-ERD): the diagram of the selected schema, or of a database without
 * schemas, loaded once per parent through `erdCache`, so a toggle or a schema revisited fetches nothing.
 * The toolbar is Table View's: breadcrumb and greyed Sync schema, then the "Column" chooser, the search
 * (no request: it dims cards) and the view toggle. The canvas, the legend and the footer go full screen
 * together.
 *
 * This view owns what must outlive the canvas's remount on entering or leaving full screen: the
 * full-screen state, the expanded cards and the open table panel. The panel is rendered beside the
 * full-screen frame and elevated above it while full screen is on (review fix R1); it opens last, so it
 * is the newest layer of the drawer's Escape stack and Escape closes it, then full screen, then the drawer.
 */
const DataFlowGraph: React.FC<Props> = ({
  api, rootName, parent, search, onSearchChange, hidden, onToggleColumn, onModeChange, erdCache,
}) => {
  const heading = useRef<HTMLHeadingElement>(null);
  useHeadingFocus(heading);

  const [erd, setErd] = useState<DataFlowErd | null>(() => erdCache.current.get(parent.key) ?? null);
  const [error, setError] = useState<DspmError | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const cache = erdCache.current;
    const key = parent.key;
    const known = cache.get(key);
    if (known) {
      setErd(known);
      return;
    }
    // Only the newest request of this view applies its answer; a cached answer is valid either way.
    let current = true;
    setError(null);
    const id = key.slice(2);
    (key.startsWith('s:') ? api.schemaErd(id) : api.databaseErd(id)).then(
      result => {
        cache.set(key, result);
        if (current) setErd(result);
      },
      err => { if (current) setError(toDspmError(err)); },
    );
    return () => { current = false; };
  }, [api, parent.key, attempt, erdCache]);

  const [fullscreen, setFullscreen] = useState(false);
  const toggle = useRef<HTMLButtonElement>(null);
  const enterFullscreen = useCallback(() => setFullscreen(true), []);
  const exitFullscreen = useCallback(() => setFullscreen(false), []);

  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set());
  const toggleExpanded = useCallback((id: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }, []);

  const [panelTable, setPanelTable] = useState<PanelTable | null>(null);
  const panelSeq = useRef(0);
  const openPanel = useCallback((table: OpenTable, opener: HTMLElement) => {
    panelSeq.current += 1;
    setPanelTable({ table, opener, seq: panelSeq.current });
  }, []);
  const closePanel = useCallback(() => setPanelTable(null), []);

  const parentName = (parent.schema ? parent.schema.name : parent.database.name) ?? '-';

  let body: React.ReactNode;
  if (error) {
    body = <ErrorNote error={error} onRetry={() => setAttempt(n => n + 1)} />;
  } else if (erd === null) {
    body = <LoadingNote />;
  } else if (erd.nodes.length === 0) {
    body = (
      <div className="rounded-xl border border-[rgba(0,0,0,0.08)]">
        <EmptyState title="No tables to show." />
      </div>
    );
  } else {
    body = (
      <FullscreenFrame open={fullscreen} title={`${parentName} diagram`} onExit={exitFullscreen} toggleRef={toggle}>
        <div className="space-y-2">
          <Suspense fallback={<LoadingNote />}>
            <ErdCanvas
              erd={erd}
              api={api}
              fullscreen={fullscreen}
              toggleRef={toggle}
              onFullscreen={enterFullscreen}
              query={searchTerm(search)}
              expanded={expanded}
              onToggleExpanded={toggleExpanded}
              onOpenTable={openPanel}
            />
          </Suspense>
          {erd.nodesTruncated && <p className="text-[11px] text-[rgba(0,0,0,0.45)]">Showing the first 1,000 tables.</p>}
          {erd.edgesTruncated && <p className="text-[11px] text-[rgba(0,0,0,0.45)]">Showing the first 3,000 relationships.</p>}
          <ErdLegend />
          <p className="text-[11px] text-[rgba(0,0,0,0.45)]">{FOOTER}</p>
        </div>
      </FullscreenFrame>
    );
  }

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        <DataFlowBreadcrumb rootName={rootName} parent={parent} heading={heading} />
        <span className="ml-auto">
          <ConsoleOnlyButton variant="secondary" icon="mdi:sync" placement="left">Sync schema</ConsoleOnlyButton>
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <ColumnsMenu label="Column" columns={COLUMNS} hidden={hidden} onToggle={onToggleColumn} />
        <SearchInput
          value={search}
          onChange={onSearchChange}
          placeholder="Search tables (min 3 characters)"
          maxLength={MAX_SEARCH}
          className="w-64"
        />
        <span className="ml-auto">
          <SegmentedToggle options={MODES} value="graph" onChange={onModeChange} label="Tables view" />
        </span>
      </div>

      {body}

      {panelTable && (
        <TablePanel
          key={panelTable.seq}
          api={api}
          table={panelTable.table}
          returnFocus={panelTable.opener}
          onClose={closePanel}
          elevated={fullscreen}
        />
      )}
    </div>
  );
};

export default DataFlowGraph;
