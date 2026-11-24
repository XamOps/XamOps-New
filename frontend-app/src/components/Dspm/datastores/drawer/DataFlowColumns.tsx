import React, { useEffect, useRef, useState } from 'react';
import type { DspmError } from '../../../../types/dspm';
import type { DataFlowColumn, DataFlowColumnPage } from '../../../../types/dspmDatastores';
import type { DatastoreDrawerApi } from '../../../../hooks/useDspmDatastoreDrawer';
import { DSPM_PAGE_SIZES } from '../../../../hooks/useDspmData';
import { toDspmError } from '../../../../hooks/useDspmDatastores';
import { DspmObjectIcon } from '../../DspmIcons';
import { ColumnsMenu } from '../ui/ColumnsMenu';
import { ConsoleOnlyButton } from '../ui/ConsoleOnly';
import { TableFooter, dataFlowPageLimit } from '../ui/TableFooter';
import type { TableParent } from './AssetHierarchy';
import { DataFlowBreadcrumb, useHeadingFocus, type OpenTable } from './DataFlowTables';
import { EmptyTableRow, ErrorNote, LoadingNote, TH, useHiddenColumns } from './TabParts';

export type ColumnColumnId = 'column' | 'type' | 'sensitives' | 'dataElements';

/** Table order; Column is always shown. */
const COLUMNS: ReadonlyArray<{ id: ColumnColumnId; label: string }> = [
  { id: 'column', label: 'Column' },
  { id: 'type', label: 'Type' },
  { id: 'sensitives', label: 'Sensitives' },
  { id: 'dataElements', label: 'Data Elements' },
];

const TD = 'px-3 py-2.5 align-middle text-xs text-[rgba(0,0,0,0.75)]';

function renderCell(column: ColumnColumnId, row: DataFlowColumn): React.ReactNode {
  switch (column) {
    case 'column':
      return (
        <span className="flex min-w-0 items-center gap-2">
          <DspmObjectIcon objectType="COLUMN" />
          <span className="max-w-[16rem] truncate font-semibold text-[rgba(0,0,0,0.85)]" title={row.name ?? undefined}>
            {row.name ?? '-'}
          </span>
        </span>
      );
    case 'type':
      return row.nativeType ?? <span className="text-[rgba(0,0,0,0.4)]">-</span>;
    case 'sensitives':
      return (
        <span className="rounded-full bg-[rgba(0,0,0,0.06)] px-2 py-0.5 text-[10px] font-semibold tabular-nums text-[rgba(0,0,0,0.6)]">
          {row.sensitiveCount ?? '-'}
        </span>
      );
    case 'dataElements':
      return row.dataElements.length > 0
        ? row.dataElements.join(', ')
        : <span className="text-[rgba(0,0,0,0.4)]">-</span>;
  }
}

/**
 * One table's columns, paged on the server (Rows Per Page 10), with their own loading and error states:
 * the Table View's columns view and the Graph View's table panel render the same rows from the same
 * endpoint (design 4.6, review fix R2). Without `hidden` every column is shown.
 */
export function DataFlowColumnsTable({ api, tableId, hidden }: {
  api: DatastoreDrawerApi;
  tableId: string;
  hidden?: ReadonlySet<ColumnColumnId>;
}) {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState<number>(DSPM_PAGE_SIZES[0]);
  const [result, setResult] = useState<DataFlowColumnPage | null>(null);
  const [error, setError] = useState<DspmError | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let current = true;
    setError(null);
    api.columns(tableId, page, pageSize).then(
      next => { if (current) setResult(next); },
      err => { if (current) setError(toDspmError(err)); },
    );
    return () => { current = false; };
  }, [api, tableId, page, pageSize, attempt]);

  const columns = COLUMNS.filter((column, index) => index === 0 || !hidden?.has(column.id));

  return (
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
                {result.rows.length === 0 && <EmptyTableRow colSpan={columns.length} searching={false} />}
                {result.rows.map((row, index) => (
                  <tr key={row.id ?? `column-${index}`} className="border-b border-[rgba(0,0,0,0.04)] last:border-b-0">
                    {columns.map(column => <td key={column.id} className={TD}>{renderCell(column.id, row)}</td>)}
                  </tr>
                ))}
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
  );
}

interface Props {
  api: DatastoreDrawerApi;
  rootName: string;
  parent: TableParent;
  table: OpenTable;
  /** Back to the tables of `parent` (the breadcrumb's parent crumb). */
  onBack: () => void;
}

/**
 * One table's columns, in place of the tables: the breadcrumb ends with the table name in bold, Sync
 * schema stays greyed, and there is no search or view toggle. The "Column 4" chooser hides columns of
 * this view only.
 */
const DataFlowColumns: React.FC<Props> = ({ api, rootName, parent, table, onBack }) => {
  const [hidden, toggleColumn] = useHiddenColumns<ColumnColumnId>();
  const heading = useRef<HTMLHeadingElement>(null);
  useHeadingFocus(heading);

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        <DataFlowBreadcrumb rootName={rootName} parent={parent} table={{ name: table.name, onBack }} heading={heading} />
        <span className="ml-auto">
          <ConsoleOnlyButton variant="secondary" icon="mdi:sync" placement="left">Sync schema</ConsoleOnlyButton>
        </span>
      </div>
      <div className="flex items-center">
        <ColumnsMenu label="Column" columns={COLUMNS} hidden={hidden} onToggle={toggleColumn} />
      </div>

      <DataFlowColumnsTable api={api} tableId={table.id} hidden={hidden} />
    </div>
  );
};

export default DataFlowColumns;
