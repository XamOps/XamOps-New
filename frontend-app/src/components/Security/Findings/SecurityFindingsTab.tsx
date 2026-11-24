import React, { useCallback, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import type { SecurityFinding } from '../../../types/security';
import { downloadCsv } from '../../../utils/csvExport';
import { usePaged } from '../../Dspm/dspmShared';
import type { DatastoreFilterState } from '../../Dspm/datastores/datastoreFilters';
import { TableFooter } from '../../Dspm/datastores/ui/TableFooter';
import FindingDrawer from './FindingDrawer';
import FindingsAnalytics from './FindingsAnalytics';
import FindingsHeader from './FindingsHeader';
import FindingsTable from './FindingsTable';
import FindingsToolbar from './FindingsToolbar';
import {
  filterMisconfigurations, findingFilterOptions, findingsCsv, findingsFileName, groupFindings,
  groupMisconfigurations, groupSizes, labelMemoryOf, summarize, type FindingColumnId, type GroupBy,
  type SecurityMisconfiguration,
} from './findingsModel';

const NO_LABELS: ReadonlyMap<string, ReadonlyMap<string, string>> = new Map();

interface Props {
  /** The AWS account the findings belong to, for the CLI fixes and the resource drawer. */
  accountId: string | null;
  /** The account's findings, already narrowed to a deep-linked resource when there is one. */
  findings: SecurityFinding[];
  /** True while findings load (also during Run Scan): the table shows its loading row. */
  loading: boolean;
  /** Why the last load failed, or null. */
  error: string | null;
  /** A forced scan is running. */
  scanning: boolean;
  onRunScan: () => void;
  onRetry: () => void;
  onResolve: (finding: SecurityFinding) => void;
  /** Finding keys with a Mark fixed request in flight. */
  resolvingKeys: readonly string[];
}

/**
 * The Security Center Findings tab, laid out and working as the XamLens Misconfigurations page: header,
 * toolbar, the two analytics cards, the table with Columns, Export and paging, and the row drawer.
 *
 * Findings of the same check become one row with its affected resources. One request loads every
 * finding; search, filters, Group By and paging run in the browser. Mark fixed sits in the drawer's
 * Affected Resources and in each resource's own drawer. A row whose last resource is marked fixed keeps
 * its drawer open, empty, so the page's Undo can bring it back in place.
 */
const SecurityFindingsTab: React.FC<Props> = ({
  accountId, findings, loading, error, scanning, onRunScan, onRetry, onResolve, resolvingKeys,
}) => {
  const rows = useMemo(() => groupFindings(findings), [findings]);
  const [search, setSearch] = useState('');
  const [filters, setFilters] = useState<DatastoreFilterState>({});
  const [groupBy, setGroupBy] = useState<GroupBy>('none');
  const [analyticsShown, setAnalyticsShown] = useState(true);
  const [opened, setOpened] = useState<SecurityMisconfiguration | null>(null);
  const failed = !loading && error !== null;
  const options = useMemo(() => (loading ? null : findingFilterOptions(rows)), [loading, rows]);
  const labelMemory = useMemo(() => (options ? labelMemoryOf(options) : NO_LABELS), [options]);
  const filtered = useMemo(() => filterMisconfigurations(rows, search, filters), [rows, search, filters]);
  const grouped = useMemo(() => groupMisconfigurations(filtered, groupBy), [filtered, groupBy]);
  const sizes = useMemo(() => groupSizes(grouped), [grouped]);
  const summary = useMemo(() => summarize(filtered), [filtered]);
  const resetKey = `${search.trim().toLowerCase()}|${JSON.stringify(filters)}|${groupBy}`;
  const paged = usePaged(grouped, resetKey);
  // Stable, because the drawer puts it in a keydown effect's dependencies.
  const closeDrawer = useCallback(() => setOpened(null), []);
  // The open row as the findings stand now, so Mark fixed shows at once in the drawer.
  const openRow = useMemo(
    () => (opened ? (rows.find(row => row.key === opened.key) ?? { ...opened, findings: [], regions: [] }) : null),
    [opened, rows],
  );
  const exportRows = (columns: Array<{ id: FindingColumnId; label: string }>) =>
    downloadCsv(findingsFileName(new Date()), findingsCsv(filtered, columns));

  return (
    <div className="space-y-5">
      <FindingsHeader scanning={scanning} onRunScan={onRunScan} />
      <FindingsToolbar
        search={search}
        onSearch={setSearch}
        groupBy={groupBy}
        onGroupBy={setGroupBy}
        options={options}
        loading={loading}
        labelMemory={labelMemory}
        filters={filters}
        onFilters={setFilters}
        analyticsShown={analyticsShown}
        onToggleAnalytics={() => setAnalyticsShown(shown => !shown)}
      />
      {analyticsShown && <FindingsAnalytics summary={summary} loading={loading} failed={failed} />}
      <section className="min-w-0 rounded-2xl border border-[rgba(0,0,0,0.08)] bg-white">
        <FindingsTable
          items={paged.items}
          groupSizes={sizes}
          totalRows={rows.length}
          matchingRows={filtered.length}
          loading={loading}
          failed={failed}
          openKey={openRow?.key ?? null}
          onOpen={setOpened}
          onExport={exportRows}
          notice={failed && (
            <div role="alert" className="flex items-start gap-2 border-b border-[rgba(0,0,0,0.06)] bg-amber-50 px-5 py-3 text-xs text-amber-800">
              <Icon icon="mdi:alert-outline" width={14} className="mt-0.5 flex-shrink-0" />
              <span className="flex-1">Could not load the security findings ({error}).</span>
              <button type="button" onClick={onRetry} className="font-semibold underline">Retry</button>
            </div>
          )}
        />
        {!loading && !failed && (
          <TableFooter
            page={paged.page}
            pageSize={paged.size}
            total={paged.total}
            onPage={paged.setPage}
            onPageSize={paged.setSize}
          />
        )}
      </section>
      {openRow && (
        <FindingDrawer
          key={openRow.key}
          row={openRow}
          allFindings={findings}
          accountId={accountId}
          onClose={closeDrawer}
          onResolve={onResolve}
          resolvingKeys={resolvingKeys}
        />
      )}
    </div>
  );
};

export default SecurityFindingsTab;
