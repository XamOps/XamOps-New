import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import type { SecurityFinding } from '../../../types/security';
import { findingKey } from '../../../hooks/useSecurityData';
import { downloadCsv } from '../../../utils/csvExport';
import { usePaged } from '../../Dspm/dspmShared';
import FilterDropdown from '../../Dspm/datastores/FilterDropdown';
import { MAX_SEARCH, type DatastoreFilterState } from '../../Dspm/datastores/datastoreFilters';
import { TH, useHiddenColumns } from '../../Dspm/datastores/drawer/TabParts';
import { ColumnsMenu } from '../../Dspm/datastores/ui/ColumnsMenu';
import { CopyButton } from '../../Dspm/datastores/ui/CopyButton';
import { EmptyState } from '../../Dspm/datastores/ui/EmptyState';
import { SearchInput } from '../../Dspm/datastores/ui/SearchInput';
import { TableFooter } from '../../Dspm/datastores/ui/TableFooter';
import { Dash } from './FindingChips';
import {
  AFFECTED_COLUMNS, REGION, affectedCsv, affectedFileName, affectedRegionOptions, filterAffected, resourceKey,
  type AffectedColumnId, type SecurityMisconfiguration,
} from './findingsModel';

interface Props {
  row: SecurityMisconfiguration;
  /** A resource clicked: the drawer opens its own drawer. `opener` gets focus back when that closes. */
  onOpenResource: (finding: SecurityFinding, opener: HTMLElement) => void;
  onResolve: (finding: SecurityFinding) => void;
  /** Finding keys with a Mark fixed request in flight. */
  resolvingKeys: readonly string[];
}

function renderCell(
  column: AffectedColumnId, finding: SecurityFinding,
  props: Pick<Props, 'onOpenResource' | 'onResolve' | 'resolvingKeys'>,
): React.ReactNode {
  switch (column) {
    case 'resource':
      return finding.resourceId ? (
        <span className="inline-flex min-w-0 items-center gap-1">
          <button
            type="button"
            data-open-resource=""
            onClick={e => props.onOpenResource(finding, e.currentTarget)}
            className="max-w-[22rem] truncate text-left text-xs font-semibold text-sky-700 hover:underline focus:outline-none focus-visible:underline"
            title={finding.resourceId}
          >
            {finding.resourceId}
          </button>
          <CopyButton text={finding.resourceId} label="Copy resource ID" />
        </span>
      ) : <Dash />;
    case 'region':
      return finding.region ? <span className="whitespace-nowrap">{finding.region}</span> : <Dash />;
    case 'actions': {
      const busy = props.resolvingKeys.includes(findingKey(finding));
      return (
        <button
          type="button"
          onClick={() => props.onResolve(finding)}
          disabled={busy}
          title="Mark fixed. If a scan finds it again, it comes back."
          aria-label={`Mark fixed: ${finding.category} on ${finding.resourceId}`}
          className="inline-flex items-center gap-1.5 whitespace-nowrap rounded border border-[rgba(0,0,0,0.12)] px-2.5 py-1 text-xs font-semibold text-[rgba(0,0,0,0.6)] transition-colors hover:border-emerald-300 hover:bg-emerald-50 hover:text-emerald-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1 disabled:cursor-not-allowed disabled:opacity-50"
        >
          <Icon icon={busy ? 'mdi:loading' : 'mdi:check-circle-outline'} width={14} className={busy ? 'animate-spin' : ''} />
          Mark fixed
        </button>
      );
    }
  }
}

/**
 * The resources the check fails on, as the XamLens drawer's Affected Datastores: a search over resource
 * ids, the Region filter, "Columns 3" (Affected Resource locked), Export of the filtered rows and
 * client-side paging. A resource id is a link to that resource's own drawer, as a datastore name is in
 * XamLens. The last column carries the Security Center's Mark fixed, where XamLens has the greyed "Push
 * to Jira".
 */
const AffectedResources: React.FC<Props> = ({ row, onOpenResource, onResolve, resolvingKeys }) => {
  const [search, setSearch] = useState('');
  const [filters, setFilters] = useState<DatastoreFilterState>({});
  const [hidden, toggleColumn] = useHiddenColumns<AffectedColumnId>();
  const options = useMemo(() => affectedRegionOptions(row.findings), [row.findings]);
  const labels = useMemo(() => new Map(options.map(option => [option.value, option.label])), [options]);
  const findings = useMemo(() => filterAffected(row.findings, search, filters), [row.findings, search, filters]);
  const paged = usePaged(findings, `${search.trim().toLowerCase()}|${(filters[REGION]?.values ?? []).join(',')}`);
  const columns = AFFECTED_COLUMNS.filter((column, index) => index === 0 || !hidden.has(column.id));
  const exportRows = () => downloadCsv(affectedFileName(row, new Date()), affectedCsv(findings, columns));
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-3">
        <SearchInput value={search} onChange={setSearch} placeholder="Search" maxLength={MAX_SEARCH} className="w-56" />
        <Icon icon="mdi:filter-outline" width={16} className="text-[rgba(0,0,0,0.45)]" aria-hidden="true" />
        <FilterDropdown
          label="Region"
          local
          dimension={REGION}
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
              {findings.length === 0 && (
                <tr>
                  <td colSpan={columns.length}>
                    {row.findings.length === 0
                      ? <EmptyState title="No affected resources." />
                      : <EmptyState title="No matches" body="Try a different search." />}
                  </td>
                </tr>
              )}
              {paged.items.map(finding => (
                <tr
                  key={resourceKey(finding)}
                  // A click anywhere on the row opens the resource, except on its own buttons. The keyboard
                  // path is the resource id, a button.
                  onClick={e => {
                    if ((e.target as HTMLElement).closest('button, a')) return;
                    const opener = e.currentTarget.querySelector<HTMLElement>('[data-open-resource]');
                    if (opener) onOpenResource(finding, opener);
                  }}
                  className="cursor-pointer border-b border-[rgba(0,0,0,0.04)] last:border-b-0 hover:bg-[rgba(0,0,0,0.015)]"
                >
                  {columns.map(column => (
                    <td key={column.id} className="px-3 py-2.5 align-middle text-xs text-[rgba(0,0,0,0.75)]">
                      {renderCell(column.id, finding, { onOpenResource, onResolve, resolvingKeys })}
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

export default AffectedResources;
