import React from 'react';
import { Icon } from '@iconify/react';
import type { MisconfigurationRow } from '../../../types/dspmMisconfigurations';
import PoweredByProvider from '../../XamSecure/PoweredByProvider';
import { abbreviate } from '../datastores/datastoreFormat';
import { TH, useHiddenColumns } from '../datastores/drawer/TabParts';
import { LabelChips, LevelLabel } from '../datastores/ui/Chips';
import { ColumnsMenu } from '../datastores/ui/ColumnsMenu';
import { EmptyState } from '../datastores/ui/EmptyState';
import FrameworkChips from './FrameworkChips';
import {
  MISCONFIGURATION_COLUMNS, type GroupedItem, type MisconfigurationColumnId,
} from './misconfigurationModel';

const DASH = <span className="text-xs text-[rgba(0,0,0,0.4)]">-</span>;
const PILL = 'inline-flex rounded-full bg-[rgba(0,0,0,0.06)] px-2 py-0.5 text-[11px] font-semibold tabular-nums text-[rgba(0,0,0,0.65)]';

function renderCell(column: MisconfigurationColumnId, row: MisconfigurationRow): React.ReactNode {
  switch (column) {
    case 'misconfiguration':
      return (
        <div className="min-w-0 max-w-[28rem]">
          <div className="truncate text-xs font-semibold text-[rgba(0,0,0,0.85)]" title={row.title ?? undefined}>
            {row.title ?? '-'}
          </div>
          {/* Upwind findings only (Matters' own carry no badge). Imported, never edited. */}
          {row.source === 'UPWIND' && <PoweredByProvider provider="upwind" className="mt-1" />}
        </div>
      );
    case 'severity':
      return <LevelLabel level={row.severity} label={row.severityLabel} />;
    case 'affected':
      return <span className={PILL}>{row.affectedResources.toLocaleString('en-US')}</span>;
    case 'sensitives':
      return <span className={PILL} title={row.sensitiveRecords.toLocaleString('en-US')}>{abbreviate(row.sensitiveRecords)}</span>;
    case 'labels':
      return row.labels.length > 0 ? <LabelChips labels={row.labels} /> : DASH;
    case 'frameworks':
      return <FrameworkChips frameworks={row.frameworks} />;
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
 * The policies with their header bar: "Columns 6" on the left (Misconfiguration locked) and Export on the
 * right. Rows open the drawer on a click or on Enter pressed on the row itself. Grouped, a header line
 * is drawn before the first row of each group on the page.
 */
const MisconfigurationTable: React.FC<Props> = ({
  items, groupSizes, totalRows, matchingRows, loading, failed, openPolicyId, onOpen, onExport, notice,
}) => {
  const [hidden, toggleColumn] = useHiddenColumns<MisconfigurationColumnId>();
  const columns = MISCONFIGURATION_COLUMNS.filter((column, index) => index === 0 || !hidden.has(column.id));

  return (
    <>
      <header className="flex flex-wrap items-center gap-3 border-b border-[rgba(0,0,0,0.06)] px-4 py-2.5">
        <ColumnsMenu columns={MISCONFIGURATION_COLUMNS} hidden={hidden} onToggle={toggleColumn} />
        <div className="ml-auto">
          <button
            type="button"
            onClick={() => onExport(columns)}
            className="inline-flex items-center gap-1.5 rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-3 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.7)] transition-colors hover:bg-[rgba(0,0,0,0.03)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)]"
          >
            <Icon icon="mdi:tray-arrow-down" width={14} />
            Export
          </button>
        </div>
      </header>
      {notice}
      <div className="overflow-x-auto">
        <table className="w-full text-left">
          <thead>
            <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
              {columns.map(column => <th key={column.id} scope="col" className={`${TH} whitespace-nowrap px-4`}>{column.label}</th>)}
            </tr>
          </thead>
          <tbody>
            {loading && (
              <tr>
                <td colSpan={columns.length} className="px-4 py-12 text-center text-xs text-[rgba(0,0,0,0.4)]">
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
                    <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.03)]">
                      <th scope="colgroup" colSpan={columns.length} className="px-4 py-2 text-left text-[11px] font-semibold text-[rgba(0,0,0,0.65)]">
                        {item.groupLabel}{' '}
                        <span className="ml-1 rounded bg-[rgba(0,0,0,0.06)] px-1.5 py-0.5 text-[10px] tabular-nums text-[rgba(0,0,0,0.55)]">
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
                    className={`cursor-pointer border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.015)] focus:outline-none focus-visible:bg-[rgba(0,0,0,0.03)] ${
                      openPolicyId === row.policyId ? 'bg-[rgba(0,0,0,0.03)]' : ''
                    }`}
                  >
                    {columns.map(column => (
                      <td key={column.id} className="px-4 py-3 align-middle text-xs text-[rgba(0,0,0,0.75)]">
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
