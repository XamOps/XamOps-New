import React from 'react';
import { Icon } from '@iconify/react';
import { TH, useHiddenColumns } from '../../Dspm/datastores/drawer/TabParts';
import { LevelLabel } from '../../Dspm/datastores/ui/Chips';
import { ColumnsMenu } from '../../Dspm/datastores/ui/ColumnsMenu';
import { EmptyState } from '../../Dspm/datastores/ui/EmptyState';
import { CategoryIcon, ChipList, Dash, NeutralChip } from './FindingChips';
import {
  FINDING_COLUMNS, frameworkLabel, type FindingColumnId, type GroupedItem, type SecurityMisconfiguration,
} from './findingsModel';

const PILL = 'inline-flex rounded-full bg-[rgba(0,0,0,0.06)] px-2 py-0.5 text-[11px] font-semibold tabular-nums text-[rgba(0,0,0,0.65)]';

function renderCell(column: FindingColumnId, row: SecurityMisconfiguration): React.ReactNode {
  switch (column) {
    case 'misconfiguration':
      return (
        <div className="min-w-0 max-w-[28rem]">
          <div className="truncate text-xs font-semibold text-[rgba(0,0,0,0.85)]" title={row.title || undefined}>
            {row.title || '-'}
          </div>
        </div>
      );
    case 'severity':
      return <LevelLabel level={row.severity} label={row.severityLabel} />;
    case 'affected':
      return <span className={PILL}>{row.findings.length.toLocaleString('en-US')}</span>;
    case 'category':
      return row.category === null ? <Dash /> : (
        <span className="inline-flex min-w-0 items-center gap-1.5">
          <CategoryIcon category={row.category} size={14} />
          <span className="max-w-[10rem] truncate" title={row.category}>{row.category}</span>
        </span>
      );
    case 'regions':
      return <ChipList values={row.regions} />;
    case 'frameworks': {
      const label = frameworkLabel(row);
      return label === null ? <Dash /> : <NeutralChip label={label} />;
    }
  }
}

interface Props {
  /** The current page's lines. */
  items: GroupedItem[];
  /** Lines per group over every page, for the group headers. */
  groupSizes: ReadonlyMap<string, number>;
  /** Rows before search and filters: zero means the account has no finding at all. */
  totalRows: number;
  /** Rows after search and filters. */
  matchingRows: number;
  /** True while the findings load: the rows are not shown. */
  loading: boolean;
  /** True when loading failed: the banner explains it, no empty state. */
  failed: boolean;
  /** The open row's key, highlighted. */
  openKey: string | null;
  onOpen: (row: SecurityMisconfiguration) => void;
  /** Export clicked: receives the visible columns, in table order. */
  onExport: (visibleColumns: Array<{ id: FindingColumnId; label: string }>) => void;
  /** Shown between the header bar and the table, e.g. the error banner. */
  notice?: React.ReactNode;
}

/**
 * The misconfigurations with their header bar: "Columns 6" on the left (Misconfiguration locked) and
 * Export on the right, as on the XamLens Misconfigurations page. Rows open the drawer on a click or on
 * Enter pressed on the row itself. Grouped, a header line is drawn before the first row of each group
 * on the page.
 */
const FindingsTable: React.FC<Props> = ({
  items, groupSizes, totalRows, matchingRows, loading, failed, openKey, onOpen, onExport, notice,
}) => {
  const [hidden, toggleColumn] = useHiddenColumns<FindingColumnId>();
  const columns = FINDING_COLUMNS.filter((column, index) => index === 0 || !hidden.has(column.id));
  return (
    <>
      <header className="flex flex-wrap items-center gap-3 border-b border-[rgba(0,0,0,0.06)] px-4 py-2.5">
        <ColumnsMenu columns={FINDING_COLUMNS} hidden={hidden} onToggle={toggleColumn} />
        <div className="ml-auto">
          <button
            type="button"
            onClick={() => onExport(columns)}
            disabled={loading}
            className="inline-flex items-center gap-1.5 rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-3 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.7)] transition-colors hover:bg-[rgba(0,0,0,0.03)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)] disabled:cursor-not-allowed disabled:opacity-50"
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
            {!loading && items.map((item, index) => {
              const { row } = item;
              const header = item.groupKey !== null && item.groupKey !== items[index - 1]?.groupKey;
              return (
                <React.Fragment key={`${item.groupKey ?? ''}|${row.key}`}>
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
                      openKey === row.key ? 'bg-[rgba(0,0,0,0.03)]' : ''
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

export default FindingsTable;
