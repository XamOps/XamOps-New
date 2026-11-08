import React, { useEffect, useRef, useState, type RefObject } from 'react';
import type { DspmError } from '../../../../types/dspm';
import type { DataFlowTable, DataFlowTablePage } from '../../../../types/dspmDatastores';
import { DSPM_PAGE_SIZES } from '../../../../hooks/useDspmData';
import { SEARCH_DEBOUNCE_MS } from '../../../../hooks/useDspmDatastores';
import { DspmObjectIcon } from '../../DspmIcons';
import { MAX_SEARCH, normalizeSearch } from '../datastoreFilters';
import { ColumnsMenu } from '../ui/ColumnsMenu';
import { ConsoleOnlyButton } from '../ui/ConsoleOnly';
import { SearchInput } from '../ui/SearchInput';
import { SegmentedToggle, type SegmentedOption } from '../ui/SegmentedToggle';
import { TableFooter, dataFlowPageLimit } from '../ui/TableFooter';
import type { TableParent } from './AssetHierarchy';
import { EmptyTableRow, ErrorNote, LoadingNote, TH, useHiddenColumns } from './TabParts';
import type { TableRequest } from './useSearchGate';

/** The answer to a table request, as the gate hands it to the view. */
export type TablesOutcome = { page: DataFlowTablePage } | { error: DspmError };

/** The tables view on screen, as `DataFlowTab`'s gate reads it: its newest request and where an answer goes. */
export interface TablesView {
  newest: TableRequest | null;
  apply: (outcome: TablesOutcome) => void;
}

/** A table whose columns can be listed. */
export interface OpenTable {
  id: string;
  name: string;
}

/** Fewer characters do not search: the backend would read every vendor page for them. */
const MIN_SEARCH = 3;

/** The search sent for the input's text: the normalised text when it has 3+ characters, else none (''). */
function searchTerm(text: string): string {
  const normalized = normalizeSearch(text);
  return normalized !== null && normalized.length >= MIN_SEARCH ? normalized : '';
}

/**
 * Design 6.7: a view switch that unmounted the focused control (a table row, the breadcrumb's back
 * crumb) leaves focus on the page body, so the new view moves it to its heading.
 */
export function useHeadingFocus(heading: RefObject<HTMLElement | null>) {
  useEffect(() => {
    const active = document.activeElement;
    if (!active || active === document.body || !active.isConnected) heading.current?.focus();
  }, [heading]);
}

const Separator: React.FC = () => <span aria-hidden="true" className="text-[rgba(0,0,0,0.3)]">›</span>;

interface BreadcrumbProps {
  rootName: string;
  parent: TableParent;
  /** In the columns view: the open table, the heading; the parent's crumb then returns to the tables. */
  table?: { name: string; onBack: () => void };
  heading: RefObject<HTMLHeadingElement | null>;
}

/**
 * "datastore › database › schema" (no schema crumb for a database without schemas). The last crumb is
 * the view's heading, focusable by script only: the parent in the tables view, the bold table name in
 * the columns view, where the parent's crumb becomes a button back to the tables.
 */
export const DataFlowBreadcrumb: React.FC<BreadcrumbProps> = ({ rootName, parent, table, heading }) => {
  const parentName = (parent.schema ? parent.schema.name : parent.database.name) ?? '-';
  return (
    <nav aria-label="Breadcrumb" className="flex min-w-0 flex-wrap items-center gap-1.5 text-xs text-[rgba(0,0,0,0.5)]">
      <span className="max-w-[10rem] truncate" title={rootName}>{rootName}</span>
      <Separator />
      {parent.schema && (
        <>
          <span className="max-w-[10rem] truncate" title={parent.database.name ?? undefined}>{parent.database.name ?? '-'}</span>
          <Separator />
        </>
      )}
      {table ? (
        <>
          <button type="button" onClick={table.onBack} className="max-w-[10rem] truncate hover:text-[rgba(0,0,0,0.8)] hover:underline">
            {parentName}
          </button>
          <Separator />
          <h4 ref={heading} tabIndex={-1} className="max-w-[14rem] truncate font-bold text-[rgba(0,0,0,0.88)] focus:outline-none">
            {table.name}
          </h4>
        </>
      ) : (
        <h4 ref={heading} tabIndex={-1} className="max-w-[14rem] truncate font-semibold text-[rgba(0,0,0,0.8)] focus:outline-none">
          {parentName}
        </h4>
      )}
    </nav>
  );
};

type TableColumnId = 'table' | 'rows' | 'foreignKeys' | 'sensitives';

/** Table order; Table is always shown. */
const COLUMNS: ReadonlyArray<{ id: TableColumnId; label: string }> = [
  { id: 'table', label: 'Table' },
  { id: 'rows', label: 'Rows' },
  { id: 'foreignKeys', label: 'Foreign Keys' },
  { id: 'sensitives', label: 'Sensitives' },
];

type TablesMode = 'table' | 'graph';

const MODES: ReadonlyArray<SegmentedOption<TablesMode>> = [
  { value: 'table', label: 'Table View' },
  { value: 'graph', label: 'Graph View', disabled: true, soon: true },
];

const TD = 'px-3 py-2.5 align-middle text-xs text-[rgba(0,0,0,0.75)]';
const DASH = <span className="text-[rgba(0,0,0,0.4)]">-</span>;

function renderCell(column: TableColumnId, table: DataFlowTable): React.ReactNode {
  switch (column) {
    case 'table':
      return (
        <span className="flex min-w-0 items-center gap-2">
          <DspmObjectIcon objectType="TABLE" />
          <span className="max-w-[16rem] truncate font-semibold text-[rgba(0,0,0,0.85)]" title={table.name ?? undefined}>
            {table.name ?? '-'}
          </span>
        </span>
      );
    case 'rows':
      return table.rowCountEstimate === null ? DASH : table.rowCountEstimate.toLocaleString('en-US');
    case 'foreignKeys':
      return table.foreignKeyCount ?? DASH;
    case 'sensitives':
      return table.sensitiveCount ?? DASH;
  }
}

interface Props {
  rootName: string;
  parent: TableParent;
  /** Sends a table request: through the search gate when it carries `q`, directly otherwise. Stable. */
  request: (r: TableRequest) => void;
  /** Where this view registers while it is on screen, so the gate can tell which answers are wanted. */
  viewRef: RefObject<TablesView | null>;
  onOpenTable: (table: OpenTable) => void;
}

/**
 * The tables of the selected schema (or schema-less database), paged on the server. The search sends
 * `q` 300 ms after typing stops, and only with 3+ characters; such requests go through the drawer's
 * search gate, plain paging does not. Answers are applied only while they are this view's newest
 * request, so a superseded page or search changes nothing.
 *
 * Graph View is not offered yet, and Sync schema is a console action. A row opens its columns.
 */
const DataFlowTables: React.FC<Props> = ({ rootName, parent, request, viewRef, onOpenTable }) => {
  const [search, setSearch] = useState('');
  const [q, setQ] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState<number>(DSPM_PAGE_SIZES[0]);
  const [result, setResult] = useState<DataFlowTablePage | null>(null);
  const [error, setError] = useState<DspmError | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [hidden, toggleColumn] = useHiddenColumns<TableColumnId>();
  const heading = useRef<HTMLHeadingElement>(null);
  useHeadingFocus(heading);

  // This view as the gate sees it. `apply` only calls state setters, which React keeps stable.
  const view = useRef<TablesView>({
    newest: null,
    apply: outcome => {
      if ('page' in outcome) {
        setResult(outcome.page);
        setError(null);
      } else {
        setError(outcome.error);
      }
    },
  });

  useEffect(() => {
    const own = view.current;
    viewRef.current = own;
    return () => { if (viewRef.current === own) viewRef.current = null; };
  }, [viewRef]);

  useEffect(() => {
    const next = searchTerm(search);
    if (next === q) return;
    const timer = window.setTimeout(() => {
      setQ(next);
      setPage(1);
    }, SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [search, q]);

  useEffect(() => {
    const next: TableRequest = { parentKey: parent.key, q, page, pageSize };
    view.current.newest = next;
    setError(null);
    request(next);
  }, [parent.key, q, page, pageSize, attempt, request]);

  const columns = COLUMNS.filter((column, index) => index === 0 || !hidden.has(column.id));

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        <DataFlowBreadcrumb rootName={rootName} parent={parent} heading={heading} />
        <span className="ml-auto">
          <ConsoleOnlyButton variant="secondary" icon="mdi:sync" placement="left">Sync schema</ConsoleOnlyButton>
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <ColumnsMenu label="Column" columns={COLUMNS} hidden={hidden} onToggle={toggleColumn} />
        <SearchInput
          value={search}
          onChange={setSearch}
          placeholder="Search tables (min 3 characters)"
          maxLength={MAX_SEARCH}
          className="w-64"
        />
        <span className="ml-auto">
          <SegmentedToggle options={MODES} value="table" onChange={() => {}} label="Tables view" />
        </span>
      </div>

      <div className="overflow-hidden rounded-xl border border-[rgba(0,0,0,0.08)]">
        {error && <div className="p-3"><ErrorNote error={error} onRetry={() => setAttempt(n => n + 1)} /></div>}
        {!error && !result && <LoadingNote />}
        {!error && result && (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-left">
                <thead>
                  <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                    {columns.map(column => <th key={column.id} scope="col" className={TH}>{column.label}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {result.rows.length === 0 && <EmptyTableRow colSpan={columns.length} searching={q !== ''} />}
                  {result.rows.map((table, index) => {
                    const id = table.id;
                    const open = id === null ? undefined : () => onOpenTable({ id, name: table.name ?? '-' });
                    return (
                      <tr
                        key={id ?? `row-${index}`}
                        tabIndex={open ? 0 : undefined}
                        onClick={open}
                        onKeyDown={open ? (e => {
                          if (e.key === 'Enter' && e.target === e.currentTarget) {
                            e.preventDefault();
                            open();
                          }
                        }) : undefined}
                        className={`border-b border-[rgba(0,0,0,0.04)] last:border-b-0 ${
                          open ? 'cursor-pointer hover:bg-[rgba(0,0,0,0.015)] focus:outline-none focus-visible:bg-[rgba(0,0,0,0.03)]' : ''
                        }`}
                      >
                        {columns.map(column => <td key={column.id} className={TD}>{renderCell(column.id, table)}</td>)}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <TableFooter
              page={page}
              pageSize={pageSize}
              total={result.total}
              onPage={setPage}
              onPageSize={size => { setPageSize(size); setPage(1); }}
              maxPage={dataFlowPageLimit(pageSize)}
            />
          </>
        )}
      </div>
      {!error && result?.searchCapped && (
        <p className="text-[11px] text-[rgba(0,0,0,0.45)]">Search covers the first 10,000 tables.</p>
      )}
    </div>
  );
};

export default DataFlowTables;
