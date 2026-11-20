import React from 'react';
import { Icon } from '@iconify/react';
import type { MisconfigurationRow } from '../../../types/dspmMisconfigurations';
import { abbreviate } from '../datastores/datastoreFormat';
import { useHiddenColumns } from '../datastores/drawer/TabParts';
import { ColumnsMenu } from '../datastores/ui/ColumnsMenu';
import { EmptyState } from '../datastores/ui/EmptyState';
import { CountBox, Dash, OUTLINED_BUTTON, OpenStatus, SeverityCell, TEXT_PRIMARY, UpwindBadge } from './ConsoleParts';
import FrameworkChips, { LabelList } from './FrameworkChips';
import {
  MISCONFIGURATION_COLUMNS, OPEN_LABEL, type GroupedItem, type MisconfigurationColumnId,
} from './misconfigurationModel';

/** The console's column widths in px; the table keeps their proportions. */
const WIDTHS: Readonly<Record<MisconfigurationColumnId, number>> = {
  misconfiguration: 400, severity: 150, status: 140, affected: 180, sensitives: 140, labels: 220, frameworks: 240, category: 200,
};

const CELL_BORDER = 'border-b border-r border-[#eeecf3] last:border-r-0';

function renderCell(column: MisconfigurationColumnId, row: MisconfigurationRow): React.ReactNode {
  switch (column) {
    case 'misconfiguration':
      return (
        <div className="flex min-w-0 flex-col items-start gap-1">
          <div className={`max-w-full truncate text-sm ${TEXT_PRIMARY}`} title={row.title ?? undefined}>
            {row.title ?? '-'}
          </div>
          {/* Upwind findings only (Matters' own carry no badge), as in the console. */}
          {row.source === 'UPWIND' && <UpwindBadge />}
        </div>
      );
    case 'severity':
      return <SeverityCell level={row.severity} label={row.severityLabel} />;
    case 'status':
      return <OpenStatus label={OPEN_LABEL} />;
    case 'affected':
      return <CountBox>{row.affectedResources.toLocaleString('en-US')}</CountBox>;
    case 'sensitives':
      return <CountBox title={row.sensitiveRecords.toLocaleString('en-US')}>{abbreviate(row.sensitiveRecords)}</CountBox>;
    case 'labels':
      return <LabelList labels={row.labels} />;
    case 'frameworks':
      return <FrameworkChips frameworks={row.frameworks} />;
    case 'category':
      // The route carries no category; the console prints "-" for an empty one.
      return <Dash />;
  }
}

interface Props {
  /** The current page's lines. */
  items: GroupedItem[];
  /** Lines per group over every page, for the group headers. */
  groupSizes: ReadonlyMap<string, number>;
  /** Rows before search and filters: zero means the project has no misconfiguration at all. */
  totalRows: number;
  /** Rows after search and filters. */
  matchingRows: number;
  /** True until the list's first answer. */
  loading: boolean;
  /** True when the list failed and nothing is loaded: the banner explains it, no empty state. */
  failed: boolean;
  /** The open row's policy id, highlighted. */
  openPolicyId: string | null;
  onOpen: (row: MisconfigurationRow) => void;
  /** Export clicked: receives the visible columns, in table order. */
  onExport: (visibleColumns: Array<{ id: MisconfigurationColumnId; label: string }>) => void;
  /** Shown between the header bar and the table, e.g. the error banner. */
  notice?: React.ReactNode;
}

/**
 * The policies with the console's header bar: "Columns 8" on the left (Misconfiguration locked) and
 * Export on the right; then a bordered table with the console's columns and widths. Rows open the drawer
 * on a click or on Enter pressed on the row itself. Grouped, a header line is drawn before the first row
 * of each group on the page.
 */
const MisconfigurationTable: React.FC<Props> = ({
  items, groupSizes, totalRows, matchingRows, loading, failed, openPolicyId, onOpen, onExport, notice,
}) => {
  const [hidden, toggleColumn] = useHiddenColumns<MisconfigurationColumnId>();
  const columns = MISCONFIGURATION_COLUMNS.filter((column, index) => index === 0 || !hidden.has(column.id));
  const totalWidth = columns.reduce((sum, column) => sum + WIDTHS[column.id], 0);

  return (
    <>
      <header className="flex items-center justify-between gap-2 border-b border-[#dddedd] px-3 py-2">
        <ColumnsMenu columns={MISCONFIGURATION_COLUMNS} hidden={hidden} onToggle={toggleColumn} />
        <button type="button" onClick={() => onExport(columns)} className={OUTLINED_BUTTON}>
          <Icon icon="mdi:tray-arrow-down" width={16} />
          Export
        </button>
      </header>
      {notice}
      <div className="overflow-x-auto">
        <table className="w-full min-w-[1280px] table-fixed border-collapse text-left">
          <colgroup>
            {columns.map(column => <col key={column.id} style={{ width: `${(WIDTHS[column.id] / totalWidth) * 100}%` }} />)}
          </colgroup>
          <thead>
            <tr className="bg-[#f9f9f9]">
              {columns.map(column => (
                <th key={column.id} scope="col" title={column.label} className={`${CELL_BORDER} truncate px-3 py-2 text-sm font-medium ${TEXT_PRIMARY}`}>
                  {column.label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {loading && (
              <tr>
                <td colSpan={columns.length} className="px-4 py-12 text-center text-sm text-[#8c8f96]">
                  Loading misconfigurations…
                </td>
              </tr>
            )}
            {!loading && !failed && matchingRows === 0 && (
              <tr>
                <td colSpan={columns.length}>
                  {totalRows === 0
                    ? <EmptyState title="No misconfigurations found." />
                    : <EmptyState title="No matches" body="Try clearing a filter or widening your search." />}
                </td>
              </tr>
            )}
            {items.map((item, index) => {
              const { row } = item;
              const header = item.groupKey !== null && item.groupKey !== items[index - 1]?.groupKey;
              return (
                <React.Fragment key={`${item.groupKey ?? ''}|${row.policyId}`}>
                  {header && (
                    <tr className="bg-[#fbfbfb]">
                      <th scope="colgroup" colSpan={columns.length} className={`border-b border-[#eeecf3] px-3 py-2 text-left text-sm font-medium ${TEXT_PRIMARY}`}>
                        {item.groupLabel}{' '}
                        <span className="ml-1 rounded-[3px] bg-[#f2f2f3] px-1.5 py-0.5 text-xs tabular-nums text-[#6b6e76]">
                          {groupSizes.get(item.groupKey!) ?? 0}
                        </span>
                      </th>
                    </tr>
                  )}
                  <tr
                    tabIndex={0}
                    onClick={() => onOpen(row)}
                    onKeyDown={e => {
                      if (e.key === 'Enter' && e.target === e.currentTarget) {
                        e.preventDefault();
                        onOpen(row);
                      }
                    }}
                    className={`cursor-pointer hover:bg-[#fafafa] focus:outline-none focus-visible:bg-[#f5f5f5] ${
                      openPolicyId === row.policyId ? 'bg-[#f5f5f5]' : ''
                    }`}
                  >
                    {columns.map(column => (
                      <td key={column.id} className={`${CELL_BORDER} overflow-hidden px-3 py-2 align-middle text-sm ${TEXT_PRIMARY}`}>
                        {renderCell(column.id, row)}
                      </td>
                    ))}
                  </tr>
                </React.Fragment>
              );
            })}
          </tbody>
        </table>
      </div>
    </>
  );
};

export default MisconfigurationTable;
