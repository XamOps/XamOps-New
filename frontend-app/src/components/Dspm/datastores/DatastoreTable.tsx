import React, { useCallback, useState } from 'react';
import { Icon } from '@iconify/react';
import type { DspmPage } from '../../../types/dspm';
import type { DatastoreRow } from '../../../types/dspmDatastores';
import type { ColumnDef, ColumnId, DatastoreTabConfig, SortField } from './datastoreTabConfig';
import { renderDatastoreCell } from './datastoreCells';
import { ColumnsMenu } from './ui/ColumnsMenu';
import { ConsoleOnlyButton, ConsoleOnlyCheckbox } from './ui/ConsoleOnly';
import { EmptyState } from './ui/EmptyState';

const HeaderCell: React.FC<{ column: ColumnDef; sort: string; onSort: (field: SortField) => void }> = ({
  column, sort, onSort,
}) => {
  const field = column.sortField;
  const active = field !== undefined && (sort === field || sort === `-${field}`);
  const descending = field !== undefined && sort === `-${field}`;
  return (
    <th
      scope="col"
      aria-sort={active ? (descending ? 'descending' : 'ascending') : undefined}
      className="whitespace-nowrap px-4 py-2.5 text-left text-[11px] font-semibold text-[rgba(0,0,0,0.5)]"
    >
      {field ? (
        <button type="button" onClick={() => onSort(field)} className="inline-flex items-center gap-1 hover:text-[rgba(0,0,0,0.75)]">
          {column.label}
          <Icon
            icon={active ? (descending ? 'mdi:arrow-down' : 'mdi:arrow-up') : 'mdi:arrow-up-down'}
            width={10}
            className={active ? 'text-[rgba(0,0,0,0.6)]' : 'text-[rgba(0,0,0,0.2)]'}
          />
        </button>
      ) : column.label}
    </th>
  );
};

interface Props {
  /** The tab's columns and whether rows lead with the console-only selection checkbox (Endpoint). */
  config: Pick<DatastoreTabConfig, 'columns' | 'selectable'>;
  page: DspmPage<DatastoreRow> | null;
  loading: boolean;
  sort: string;
  onSort: (field: SortField) => void;
  /** Whether a row opens a drawer. Rows that do not get no click handler and are not focusable. */
  canOpen: (row: DatastoreRow) => boolean;
  onOpen: (row: DatastoreRow) => void;
  /** Export clicked: receives the visible columns, in table order. */
  onExport: (visibleColumns: ColumnDef[]) => void;
  /** While true, Export reads "Exporting…" and is disabled. */
  exporting: boolean;
  /** Shown between the header bar and the table, e.g. the error banner. */
  notice?: React.ReactNode;
}

/**
 * The Datastores list with its header bar: the "Columns N" chooser on the left, the Export button on the
 * right. Hidden columns are this component's own state, so they reset with the tab.
 *
 * Only Datastore / Device, Risk and Sensitive Data sort; the API has no sort field for the others. Every
 * row ends with the console-only ⋮ menu. An openable row is focusable and opens on click, or on Enter
 * pressed on the row itself: Enter on a control inside the row (the ⋮, the Endpoint checkbox) never
 * opens it.
 */
const DatastoreTable: React.FC<Props> = ({
  config, page, loading, sort, onSort, canOpen, onOpen, onExport, exporting, notice,
}) => {
  const [hidden, setHidden] = useState<ReadonlySet<ColumnId>>(new Set());
  const toggleColumn = useCallback((id: ColumnId) => {
    setHidden(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }, []);

  const { selectable } = config;
  const columns = config.columns.filter((column, index) => index === 0 || !hidden.has(column.id));
  const nowMs = Date.now();
  const span = columns.length + (selectable ? 2 : 1);

  return (
    <>
      <header className="flex flex-wrap items-center gap-3 border-b border-[rgba(0,0,0,0.06)] px-4 py-2.5">
        <ColumnsMenu columns={config.columns} hidden={hidden} onToggle={toggleColumn} />
        <div className="ml-auto">
          <button
            type="button"
            onClick={() => onExport(columns)}
            disabled={exporting}
            aria-busy={exporting}
            className="inline-flex items-center gap-1.5 rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-3 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.7)] transition-colors hover:bg-[rgba(0,0,0,0.03)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)] disabled:cursor-progress disabled:hover:bg-white"
          >
            <Icon icon="mdi:tray-arrow-down" width={14} />
            {exporting ? 'Exporting…' : 'Export'}
          </button>
        </div>
      </header>
      {notice}
      <div className="overflow-x-auto">
        <table className="w-full text-left">
          <thead>
            <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
              {selectable && (
                <th scope="col" className="w-10 px-4 py-2.5">
                  <ConsoleOnlyCheckbox label="Select all rows" placement="right" />
                </th>
              )}
              {columns.map(column => <HeaderCell key={column.id} column={column} sort={sort} onSort={onSort} />)}
              <th scope="col" className="w-12 px-4 py-2.5"><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {loading && !page && (
              <tr>
                <td colSpan={span} className="px-4 py-12 text-center text-xs text-[rgba(0,0,0,0.4)]">Loading datastores…</td>
              </tr>
            )}

            {page && page.data.length === 0 && (
              <tr>
                <td colSpan={span}>
                  <EmptyState title="No datastores match" body="Try clearing a filter or widening your search." />
                </td>
              </tr>
            )}

            {page?.data.map((row, index) => {
              const openable = canOpen(row);
              return (
                <tr
                  key={row.id ?? row.sourceId ?? `row-${index}`}
                  onClick={openable ? () => onOpen(row) : undefined}
                  onKeyDown={openable ? (e => {
                    if (e.key === 'Enter' && e.target === e.currentTarget) {
                      e.preventDefault();
                      onOpen(row);
                    }
                  }) : undefined}
                  tabIndex={openable ? 0 : undefined}
                  className={`border-b border-[rgba(0,0,0,0.04)] ${
                    openable ? 'cursor-pointer hover:bg-[rgba(0,0,0,0.015)] focus:outline-none focus-visible:bg-[rgba(0,0,0,0.03)]' : ''
                  }`}
                >
                  {selectable && (
                    <td className="px-4 py-3">
                      <ConsoleOnlyCheckbox label={`Select ${row.name ?? 'row'}`} placement="right" />
                    </td>
                  )}
                  {columns.map(column => (
                    <td key={column.id} className="px-4 py-3 align-middle">{renderDatastoreCell(column.id, row, nowMs)}</td>
                  ))}
                  <td className="px-4 py-3 text-right">
                    <ConsoleOnlyButton variant="icon" icon="mdi:dots-vertical" label="Row actions" placement="left" />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </>
  );
};

export default DatastoreTable;
