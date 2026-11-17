import React, { useCallback, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreFilterOption, DatastoreMisconfiguration, DatastoreRisks } from '../../../../types/dspmDatastores';
import PoweredByProvider from '../../../XamSecure/PoweredByProvider';
import { usePaged } from '../../dspmShared';
import FilterDropdown from '../FilterDropdown';
import { MAX_SEARCH, type DatastoreFilterState } from '../datastoreFilters';
import { LevelLabel } from '../ui/Chips';
import { ColumnsMenu } from '../ui/ColumnsMenu';
import { EmptyState } from '../ui/EmptyState';
import { SearchInput } from '../ui/SearchInput';
import { SegmentedToggle, type SegmentedOption } from '../ui/SegmentedToggle';
import { TableFooter } from '../ui/TableFooter';
import RiskDetailPanel from './RiskDetailPanel';
import { TH, useHiddenColumns } from './TabParts';

type RiskColumnId = 'misconfiguration' | 'type' | 'severity';

/** Table order; Misconfiguration is always shown. */
const COLUMNS: ReadonlyArray<{ id: RiskColumnId; label: string }> = [
  { id: 'misconfiguration', label: 'Misconfiguration' },
  { id: 'type', label: 'Misconfiguration Type' },
  { id: 'severity', label: 'Severity' },
];

type RiskView = 'misconfigurations' | 'alerts';

const VIEWS: ReadonlyArray<SegmentedOption<RiskView>> = [
  { value: 'misconfigurations', label: 'Misconfigurations' },
  { value: 'alerts', label: 'Alerts', disabled: true, soon: true },
];

/** The filter state's only dimension. */
const SEVERITY = 'severity';

/** Critical > High > Medium > Low > anything else (A20). */
const SEVERITY_RANK: Readonly<Record<string, number>> = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1 };
const rankOf = (severity: string | null) => SEVERITY_RANK[severity ?? ''] ?? 0;

/** API order until the header is clicked; then highest first, and each click flips it. */
type SeveritySort = 'api' | 'desc' | 'asc';

const NO_VALUES: string[] = [];

/** The severities present with their row counts, highest first: the Severity filter's options. */
function severityOptions(items: DatastoreMisconfiguration[]): DatastoreFilterOption[] {
  const found = new Map<string, DatastoreFilterOption>();
  for (const item of items) {
    if (item.severity === null) continue;
    const option = found.get(item.severity);
    if (option) option.count = (option.count ?? 0) + 1;
    else found.set(item.severity, { value: item.severity, label: item.severityLabel ?? item.severity, count: 1 });
  }
  return [...found.values()].sort((a, b) => rankOf(b.value) - rankOf(a.value));
}

/** One finding with its position in the API list, which keys the row and the open panel. */
interface IndexedItem {
  item: DatastoreMisconfiguration;
  index: number;
}

function renderCell(column: RiskColumnId, item: DatastoreMisconfiguration): React.ReactNode {
  switch (column) {
    case 'misconfiguration':
      return (
        <span className="inline-flex flex-wrap items-center font-semibold text-[rgba(0,0,0,0.85)]">
          {item.title ?? '-'}
          {/* Upwind findings only; no badge for MATTERS (G5). Imported, never edited (D5). */}
          {item.source === 'UPWIND' && <PoweredByProvider provider="upwind" className="ml-2" />}
        </span>
      );
    case 'type':
      return <span className="text-[rgba(0,0,0,0.4)]">-</span>;   // no API field (G4)
    case 'severity':
      return <LevelLabel level={item.severity} label={item.severityLabel} />;
  }
}

/**
 * The Risk tab: Misconfigurations (Alerts is not offered yet), searched by title, filtered by the
 * severities present and paged in the browser. Severity sorts Critical > High > Medium > Low > other;
 * until its header is clicked the rows keep the API order.
 *
 * A row opens the detail panel on a click or on Enter pressed on the row itself; the panel returns
 * focus to the row when it closes.
 */
const RiskTab: React.FC<{ risks: DatastoreRisks }> = ({ risks }) => {
  const { items } = risks;
  const [search, setSearch] = useState('');
  const [filters, setFilters] = useState<DatastoreFilterState>({});
  const [sort, setSort] = useState<SeveritySort>('api');
  const [hidden, toggleColumn] = useHiddenColumns<RiskColumnId>();
  // `seq` keys the panel, so every opening is a fresh panel that takes focus and registers its row.
  const [open, setOpen] = useState<{ index: number; opener: HTMLElement; seq: number } | null>(null);
  const closePanel = useCallback(() => setOpen(null), []);

  const options = useMemo(() => severityOptions(items), [items]);
  const labels = useMemo(() => new Map(options.map(option => [option.value, option.label])), [options]);
  const selected = filters[SEVERITY]?.values ?? NO_VALUES;
  const needle = search.trim().toLowerCase();

  const rows = useMemo(() => {
    const matching = items
      .map((item, index): IndexedItem => ({ item, index }))
      .filter(({ item }) => (needle === '' || (item.title ?? '').toLowerCase().includes(needle))
        && (selected.length === 0 || (item.severity !== null && selected.includes(item.severity))));
    if (sort === 'api') return matching;
    const direction = sort === 'desc' ? -1 : 1;
    return [...matching].sort((a, b) => direction * (rankOf(a.item.severity) - rankOf(b.item.severity)));
  }, [items, needle, selected, sort]);

  const paged = usePaged(rows, `${needle}|${selected.join(',')}|${sort}`);
  const columns = COLUMNS.filter((column, index) => index === 0 || !hidden.has(column.id));
  const openRow = (index: number, row: HTMLElement) => setOpen(prev => ({ index, opener: row, seq: (prev?.seq ?? 0) + 1 }));

  const header = (column: { id: RiskColumnId; label: string }) => {
    if (column.id !== 'severity') return <th key={column.id} scope="col" className={TH}>{column.label}</th>;
    return (
      <th
        key={column.id}
        scope="col"
        aria-sort={sort === 'api' ? undefined : sort === 'desc' ? 'descending' : 'ascending'}
        className={TH}
      >
        <button
          type="button"
          onClick={() => setSort(current => (current === 'desc' ? 'asc' : 'desc'))}
          className="inline-flex items-center gap-1 hover:text-[rgba(0,0,0,0.75)]"
        >
          {column.label}
          <Icon
            icon={sort === 'api' ? 'mdi:arrow-up-down' : sort === 'desc' ? 'mdi:arrow-down' : 'mdi:arrow-up'}
            width={10}
            className={sort === 'api' ? 'text-[rgba(0,0,0,0.2)]' : 'text-[rgba(0,0,0,0.6)]'}
          />
        </button>
      </th>
    );
  };

  // The panel sits outside the spaced column: `space-y` margins would push an absolutely placed box down.
  return (
    <>
      <div className="space-y-3">
        <div className="flex flex-wrap items-center gap-3">
          <SegmentedToggle options={VIEWS} value="misconfigurations" onChange={() => {}} label="Risk view" />
          <SearchInput value={search} onChange={setSearch} placeholder="Search issues" maxLength={MAX_SEARCH} className="w-60" />
          <FilterDropdown
            label="Severity"
            icon="mdi:filter-outline"
            local
            dimension={SEVERITY}
            options={options}
            hasMore={false}
            filters={filters}
            labelMemory={labels}
            loading={false}
            error={false}
            onChange={setFilters}
          />
          <ColumnsMenu columns={COLUMNS} hidden={hidden} onToggle={toggleColumn} />
        </div>

        <div className="overflow-hidden rounded-xl border border-[rgba(0,0,0,0.08)]">
          <div className="overflow-x-auto">
            <table className="w-full text-left">
              <thead>
                <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                  {columns.map(header)}
                </tr>
              </thead>
              <tbody>
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={columns.length}>
                      {items.length === 0
                        ? <EmptyState title="No misconfigurations found for this datastore." />
                        : <EmptyState title="No matches" body="Try a different search." />}
                    </td>
                  </tr>
                )}
                {paged.items.map(({ item, index }) => (
                  <tr
                    key={index}
                    tabIndex={0}
                    onClick={e => openRow(index, e.currentTarget)}
                    onKeyDown={e => {
                      if (e.key === 'Enter' && e.target === e.currentTarget) {
                        e.preventDefault();
                        openRow(index, e.currentTarget);
                      }
                    }}
                    className={`cursor-pointer border-b border-[rgba(0,0,0,0.04)] last:border-b-0 hover:bg-[rgba(0,0,0,0.015)] focus:outline-none focus-visible:bg-[rgba(0,0,0,0.03)] ${
                      open?.index === index ? 'bg-[rgba(0,0,0,0.03)]' : ''
                    }`}
                  >
                    {columns.map(column => (
                      <td key={column.id} className="px-3 py-2.5 align-middle text-xs text-[rgba(0,0,0,0.75)]">
                        {renderCell(column.id, item)}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <TableFooter
            page={paged.page}
            pageSize={paged.size}
            total={paged.total}
            onPage={paged.setPage}
            onPageSize={paged.setSize}
          />
        </div>
      </div>

      {open && (
        <RiskDetailPanel key={open.seq} item={items[open.index]} returnFocus={open.opener} onClose={closePanel} />
      )}
    </>
  );
};

export default RiskTab;
