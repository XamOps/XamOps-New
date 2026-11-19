import React, { useCallback, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import type { DspmStatus } from '../../../types/dspm';
import type { DatastoreRow } from '../../../types/dspmDatastores';
import type { MisconfigurationRow } from '../../../types/dspmMisconfigurations';
import { useMisconfigurationList } from '../../../hooks/useDspmMisconfigurations';
import { downloadCsv } from '../../../utils/csvExport';
import DspmNotReady from '../DspmNotReady';
import { usePaged } from '../dspmShared';
import { datastoreErrorCopy } from '../datastores/datastoreErrors';
import type { DatastoreFilterState } from '../datastores/datastoreFilters';
import DatastoreDrawer, { type DrawerRow } from '../datastores/drawer/DatastoreDrawer';
import { TableFooter } from '../datastores/ui/TableFooter';
import MisconfigurationAnalytics from './MisconfigurationAnalytics';
import MisconfigurationDrawer, { type MisconfigurationSection } from './MisconfigurationDrawer';
import MisconfigurationHeader from './MisconfigurationHeader';
import MisconfigurationTable from './MisconfigurationTable';
import MisconfigurationToolbar from './MisconfigurationToolbar';
import {
  filterMisconfigurations, groupMisconfigurations, groupSizes, isOpenableDatastore, labelMemoryOf,
  misconfigurationCsv, misconfigurationFilterOptions, misconfigurationsFileName, type GroupBy,
} from './misconfigurationModel';

const NO_ROWS: MisconfigurationRow[] = [];

/**
 * The ready page: header, toolbar, the two greyed cards, the table with Export and paging, and the row drawer.
 *
 * One request loads every policy; search, filters, Group By and paging run in the browser. A datastore
 * link in the drawer closes it and opens that datastore's drawer; closing that one brings the
 * misconfiguration drawer back on Affected Datastores. Two root drawers are never open together.
 */
const ReadyMisconfigurationsPage: React.FC = () => {
  const list = useMisconfigurationList();
  const [search, setSearch] = useState('');
  const [filters, setFilters] = useState<DatastoreFilterState>({});
  const [groupBy, setGroupBy] = useState<GroupBy>('none');
  const [analyticsShown, setAnalyticsShown] = useState(true);
  const [open, setOpen] = useState<{ row: MisconfigurationRow; section: MisconfigurationSection } | null>(null);
  const [datastore, setDatastore] = useState<DrawerRow | null>(null);

  const rows = list.data?.rows ?? NO_ROWS;
  const options = useMemo(() => (list.data ? misconfigurationFilterOptions(list.data.rows) : null), [list.data]);
  const labelMemory = useMemo(() => (options ? labelMemoryOf(options) : new Map()), [options]);
  const filtered = useMemo(() => filterMisconfigurations(rows, search, filters), [rows, search, filters]);
  const grouped = useMemo(() => groupMisconfigurations(filtered, groupBy), [filtered, groupBy]);
  const sizes = useMemo(() => groupSizes(grouped), [grouped]);
  const resetKey = `${search.trim().toLowerCase()}|${JSON.stringify(filters)}|${groupBy}`;
  const paged = usePaged(grouped, resetKey);

  // Stable, because the drawers put them in a keydown effect's dependencies.
  const closeMisconfiguration = useCallback(() => setOpen(null), []);
  const openDatastore = useCallback((row: DatastoreRow) => {
    if (isOpenableDatastore(row)) setDatastore(row);
  }, []);
  const closeDatastore = useCallback(() => {
    setDatastore(null);
    setOpen(prev => (prev ? { ...prev, section: 'affected' } : prev));
  }, []);

  const exportRows = (columns: Parameters<typeof misconfigurationCsv>[1]) =>
    downloadCsv(misconfigurationsFileName(new Date()), misconfigurationCsv(filtered, columns));

  const errorCopy = list.error ? datastoreErrorCopy(list.error) : null;

  return (
    <div className="space-y-5">
      <MisconfigurationHeader />
      <MisconfigurationToolbar
        search={search}
        onSearch={setSearch}
        groupBy={groupBy}
        onGroupBy={setGroupBy}
        options={options}
        loading={list.loading}
        labelMemory={labelMemory}
        filters={filters}
        onFilters={setFilters}
        analyticsShown={analyticsShown}
        onToggleAnalytics={() => setAnalyticsShown(shown => !shown)}
      />
      {/* Greyed, with no figures (BUILD-BRIEF-5 §11): the list's totals are never shown. */}
      {analyticsShown && <MisconfigurationAnalytics />}

      <section className="min-w-0 rounded-2xl border border-[rgba(0,0,0,0.08)] bg-white">
        <MisconfigurationTable
          items={paged.items}
          groupSizes={sizes}
          totalRows={rows.length}
          matchingRows={filtered.length}
          loading={list.data === null && list.error === null}
          failed={list.data === null && list.error !== null}
          openPolicyId={open?.row.policyId ?? null}
          onOpen={row => setOpen({ row, section: 'details' })}
          onExport={exportRows}
          notice={(
            <>
              {list.data?.capped && (
                <div role="status" className="flex items-start gap-2 border-b border-[rgba(0,0,0,0.06)] bg-sky-50 px-5 py-3 text-xs text-sky-800">
                  <Icon icon="mdi:information-outline" width={14} className="mt-0.5 flex-shrink-0" />
                  <span className="flex-1">
                    Showing misconfigurations of the first {list.data.datastoresRead.toLocaleString('en-US')} datastores.
                  </span>
                </div>
              )}
              {errorCopy && (
                <div role="alert" className="flex items-start gap-2 border-b border-[rgba(0,0,0,0.06)] bg-amber-50 px-5 py-3 text-xs text-amber-800">
                  <Icon icon="mdi:alert-outline" width={14} className="mt-0.5 flex-shrink-0" />
                  <span className="flex-1">{errorCopy.text}</span>
                  {errorCopy.retry && (
                    <button type="button" onClick={list.reload} className="font-semibold underline">Retry</button>
                  )}
                </div>
              )}
            </>
          )}
        />
        {list.data && (
          <TableFooter
            page={paged.page}
            pageSize={paged.size}
            total={paged.total}
            onPage={paged.setPage}
            onPageSize={paged.setSize}
          />
        )}
      </section>

      {open && !datastore && (
        <MisconfigurationDrawer
          key={open.row.policyId}
          row={open.row}
          initialSection={open.section}
          onClose={closeMisconfiguration}
          onOpenDatastore={openDatastore}
        />
      )}
      {datastore && <DatastoreDrawer key={datastore.sourceId} row={datastore} onClose={closeDatastore} />}
    </div>
  );
};

interface Props {
  status: DspmStatus;
  onRetry: () => void;
}

/**
 * XamLens Misconfigurations: one row per policy across the project's cloud and on-prem datastores, read
 * from the per-datastore issues route. A not-ready workspace shows its own explanation, as the
 * Datastores page does.
 */
const MisconfigurationsPage: React.FC<Props> = ({ status, onRetry }) => {
  if (!status.ready) return <DspmNotReady status={status} onRetry={onRetry} />;
  return <ReadyMisconfigurationsPage />;
};

export default MisconfigurationsPage;
