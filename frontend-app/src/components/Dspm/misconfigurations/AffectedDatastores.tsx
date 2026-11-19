import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreRow } from '../../../types/dspmDatastores';
import type { AffectedDatastore } from '../../../types/dspmMisconfigurations';
import { downloadCsv } from '../../../utils/csvExport';
import { DspmProviderIcon } from '../DspmIcons';
import { usePaged } from '../dspmShared';
import FilterDropdown from '../datastores/FilterDropdown';
import { accountIcon } from '../datastores/datastoreCells';
import { MAX_SEARCH, type DatastoreFilterState } from '../datastores/datastoreFilters';
import { abbreviate, formatListDate } from '../datastores/datastoreFormat';
import { datastoreIconKey } from '../datastores/datastoreVisuals';
import { TH, useHiddenColumns } from '../datastores/drawer/TabParts';
import { LabelChips } from '../datastores/ui/Chips';
import { ColumnsMenu } from '../datastores/ui/ColumnsMenu';
import { ConsoleOnlyButton } from '../datastores/ui/ConsoleOnly';
import { EmptyState } from '../datastores/ui/EmptyState';
import { SearchInput } from '../datastores/ui/SearchInput';
import { TableFooter } from '../datastores/ui/TableFooter';
import {
  ACCOUNT, AFFECTED_COLUMNS, affectedAccountOptions, affectedCsv, affectedFileName, filterAffected,
  isOpenableDatastore, type AffectedColumnId,
} from './misconfigurationModel';

const DASH = <span className="text-xs text-[rgba(0,0,0,0.4)]">-</span>;
const PILL = 'inline-flex rounded-full bg-[rgba(0,0,0,0.06)] px-2 py-0.5 text-[11px] font-semibold tabular-nums text-[rgba(0,0,0,0.65)]';

function renderCell(
  column: AffectedColumnId, item: AffectedDatastore, onOpenDatastore: (row: DatastoreRow) => void,
): React.ReactNode {
  const row = item.datastore;
  switch (column) {
    case 'datastore':
      return (
        <span className="inline-flex min-w-0 items-center gap-2">
          <DspmProviderIcon provider={datastoreIconKey(row)} size={16} />
          {isOpenableDatastore(row) ? (
            <button
              type="button"
              onClick={() => onOpenDatastore(row)}
              className="max-w-[16rem] truncate text-left text-xs font-semibold text-sky-700 hover:underline"
              title={row.name ?? undefined}
            >
              {row.name ?? '-'}
            </button>
          ) : (
            <span className="max-w-[16rem] truncate text-xs font-semibold text-[rgba(0,0,0,0.8)]" title={row.name ?? undefined}>
              {row.name ?? '-'}
            </span>
          )}
        </span>
      );
    case 'type':
      return row.subtypeLabel ?? row.subtype ?? DASH;
    case 'account':
      return (
        <span className="inline-flex min-w-0 items-center gap-1.5">
          {accountIcon(row)}
          <span className="max-w-[12rem] truncate" title={row.source.accountLabel ?? undefined}>{row.source.accountLabel ?? '-'}</span>
        </span>
      );
    case 'sensitives':
      return (
        <span className={PILL} title={row.sensitives.recordCount.toLocaleString('en-US')}>
          {abbreviate(row.sensitives.recordCount)}
        </span>
      );
    case 'labels':
      return row.sensitives.typeLabels.length > 0 ? <LabelChips labels={row.sensitives.typeLabels} /> : DASH;
    case 'lastScanned':
      return item.lastScannedAt === null ? DASH : <span className="whitespace-nowrap">{formatListDate(item.lastScannedAt)}</span>;
    case 'jira':
      return <ConsoleOnlyButton variant="link" placement="left">Push to Jira</ConsoleOnlyButton>;
  }
}

interface Props {
  policyId: string;
  affected: AffectedDatastore[];
  /** A datastore name clicked: the page opens that datastore's drawer. */
  onOpenDatastore: (row: DatastoreRow) => void;
}

/**
 * The datastores that report the policy: a search over names, the Account Name filter, "Columns 7"
 * (Affected Datastore locked), Export of the filtered rows and client-side paging. A name with a 24-hex
 * source id is a link to the datastore drawer. The console's Status column and filter have no API data
 * and are left out; its Jira column keeps only the greyed "Push to Jira".
 */
const AffectedDatastores: React.FC<Props> = ({ policyId, affected, onOpenDatastore }) => {
  const [search, setSearch] = useState('');
  const [filters, setFilters] = useState<DatastoreFilterState>({});
  const [hidden, toggleColumn] = useHiddenColumns<AffectedColumnId>();

  const options = useMemo(() => affectedAccountOptions(affected), [affected]);
  const labels = useMemo(() => new Map(options.map(option => [option.value, option.label])), [options]);
  const rows = useMemo(() => filterAffected(affected, search, filters), [affected, search, filters]);
  const paged = usePaged(rows, `${search.trim().toLowerCase()}|${(filters[ACCOUNT]?.values ?? []).join(',')}`);
  const columns = AFFECTED_COLUMNS.filter((column, index) => index === 0 || !hidden.has(column.id));

  const exportRows = () => downloadCsv(affectedFileName(policyId, new Date()), affectedCsv(rows, columns));

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-3">
        <SearchInput value={search} onChange={setSearch} placeholder="Search" maxLength={MAX_SEARCH} className="w-56" />
        <Icon icon="mdi:filter-outline" width={16} className="text-[rgba(0,0,0,0.45)]" aria-hidden="true" />
        <FilterDropdown
          label="Account Name"
          local
          dimension={ACCOUNT}
          options={options}
          hasMore={false}
          filters={filters}
          labelMemory={labels}
          loading={false}
          error={false}
          onChange={setFilters}
        />
      </div>

      <div className="overflow-hidden rounded-xl border border-[rgba(0,0,0,0.08)]">
        <header className="flex flex-wrap items-center gap-3 border-b border-[rgba(0,0,0,0.06)] px-3 py-2">
          <ColumnsMenu columns={AFFECTED_COLUMNS} hidden={hidden} onToggle={toggleColumn} />
          <div className="ml-auto">
            <button
              type="button"
              onClick={exportRows}
              className="inline-flex items-center gap-1.5 rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-3 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.7)] transition-colors hover:bg-[rgba(0,0,0,0.03)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)]"
            >
              <Icon icon="mdi:tray-arrow-down" width={14} />
              Export
            </button>
          </div>
        </header>
        <div className="overflow-x-auto">
          <table className="w-full text-left">
            <thead>
              <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                {columns.map(column => <th key={column.id} scope="col" className={`${TH} whitespace-nowrap`}>{column.label}</th>)}
              </tr>
            </thead>
            <tbody>
              {rows.length === 0 && (
                <tr>
                  <td colSpan={columns.length}>
                    {affected.length === 0
                      ? <EmptyState title="No affected datastores." />
                      : <EmptyState title="No matches" body="Try a different search." />}
                  </td>
                </tr>
              )}
              {paged.items.map((item, index) => (
                <tr key={item.datastore.sourceId ?? item.datastore.id ?? `row-${index}`} className="border-b border-[rgba(0,0,0,0.04)] last:border-b-0">
                  {columns.map(column => (
                    <td key={column.id} className="px-3 py-2.5 align-middle text-xs text-[rgba(0,0,0,0.75)]">
                      {renderCell(column.id, item, onOpenDatastore)}
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
  );
};

export default AffectedDatastores;
