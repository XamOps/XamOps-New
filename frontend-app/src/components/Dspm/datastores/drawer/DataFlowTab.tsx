import React, { useCallback, useRef, useState } from 'react';
import type { DspmError } from '../../../../types/dspm';
import type { DataFlowDatabases, DataFlowTablePage, DatastoreRow } from '../../../../types/dspmDatastores';
import type { DatastoreDrawerApi, LazyResource } from '../../../../hooks/useDspmDatastoreDrawer';
import { toDspmError } from '../../../../hooks/useDspmDatastores';
import { EmptyState } from '../ui/EmptyState';
import AssetHierarchy, { type TableParent } from './AssetHierarchy';
import DataFlowColumns from './DataFlowColumns';
import DataFlowTables, { type OpenTable, type TablesOutcome, type TablesView } from './DataFlowTables';
import { ErrorNote, LoadingNote, NOT_SCANNED_TEXT } from './TabParts';
import { sameRequest, useSearchGate, type TableRequest } from './useSearchGate';

/** The tables of a schema (`s:<id>`) or of a database without schemas (`d:<id>`). */
function fetchTables(api: DatastoreDrawerApi, r: TableRequest): Promise<DataFlowTablePage> {
  const id = r.parentKey.slice(2);
  const q = r.q === '' ? null : r.q;
  return r.parentKey.startsWith('s:')
    ? api.schemaTables(id, r.page, r.pageSize, q)
    : api.databaseTables(id, r.page, r.pageSize, q);
}

interface Props {
  api: DatastoreDrawerApi;
  row: Pick<DatastoreRow, 'name' | 'source'>;
  /** Loaded by the drawer on the tab's first activation. */
  databases: LazyResource<DataFlowDatabases>;
}

/**
 * The Data Flow tab, Table View (design 6.11): the Asset Hierarchy on the left, and on the right the
 * selected schema's tables or one table's columns. Every Data Flow failure, a 404 included, shows in the
 * right pane only, so the tree stays usable.
 *
 * This tab owns the one-search-at-a-time gate and hands it to each tables view it shows: a table search
 * waits for the pending one, also across a schema switch, so two full-schema reads never overlap.
 * `send` and `isWanted` read the view on screen through a ref, so the gate keeps one identity, and
 * `send` never rejects: a failure becomes the view's error state.
 */
const DataFlowTab: React.FC<Props> = ({ api, row, databases }) => {
  const [parent, setParent] = useState<TableParent | null>(null);
  const [openTable, setOpenTable] = useState<OpenTable | null>(null);
  const [treeError, setTreeError] = useState<{ error: DspmError; retry: () => void } | null>(null);

  const view = useRef<TablesView | null>(null);
  const send = useCallback(async (r: TableRequest) => {
    let outcome: TablesOutcome;
    try {
      outcome = { page: await fetchTables(api, r) };
    } catch (err) {
      outcome = { error: toDspmError(err) };
    }
    const current = view.current;
    if (current?.newest && sameRequest(current.newest, r)) current.apply(outcome);
  }, [api]);
  const isWanted = useCallback((r: TableRequest) => {
    const newest = view.current?.newest;
    return newest != null && sameRequest(newest, r);
  }, []);
  const runSearch = useSearchGate(send, isWanted);
  // Plain paging and a freshly selected schema cost one or two vendor pages, so they skip the gate.
  const requestTables = useCallback((r: TableRequest) => {
    if (r.q === '') void send(r);
    else runSearch(r);
  }, [send, runSearch]);

  // Selecting a schema or database in the tree, the current one included, shows its tables.
  const select = useCallback((next: TableParent) => {
    setTreeError(null);
    setOpenTable(null);
    setParent(current => (current?.key === next.key ? current : next));
  }, []);
  const reportTreeError = useCallback((error: DspmError, retry: () => void) => setTreeError({ error, retry }), []);

  const rootName = row.name ?? '-';
  let pane: React.ReactNode;
  if (databases.error) {
    pane = <ErrorNote error={databases.error} onRetry={databases.reload} />;
  } else if (treeError) {
    pane = <ErrorNote error={treeError.error} onRetry={() => { setTreeError(null); treeError.retry(); }} />;
  } else if (databases.data === null) {
    pane = <LoadingNote />;
  } else if (databases.data.items.length === 0) {
    pane = <EmptyState title={NOT_SCANNED_TEXT} />;
  } else if (parent === null) {
    pane = <LoadingNote />;   // the first schema is being selected (DA1)
  } else if (openTable) {
    pane = (
      <DataFlowColumns
        key={openTable.id}
        api={api}
        rootName={rootName}
        parent={parent}
        table={openTable}
        onBack={() => setOpenTable(null)}
      />
    );
  } else {
    pane = (
      <DataFlowTables
        key={parent.key}
        rootName={rootName}
        parent={parent}
        request={requestTables}
        viewRef={view}
        onOpenTable={setOpenTable}
      />
    );
  }

  return (
    <div className="flex items-start gap-4">
      <AssetHierarchy
        api={api}
        row={row}
        databases={databases.data}
        selectedKey={parent?.key ?? null}
        onSelect={select}
        onError={reportTreeError}
      />
      <div className="min-w-0 flex-1">{pane}</div>
    </div>
  );
};

export default DataFlowTab;
