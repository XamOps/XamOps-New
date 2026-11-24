import React, { useCallback, useEffect, useId, useState } from 'react';
import { Icon } from '@iconify/react';
import type { DspmStatus } from '../../../types/dspm';
import type { RemediationActivity, RemediationItem } from '../../../types/dspmPages';
import { pagesApi } from '../../../hooks/useDspmPages';
import { useDebouncedText, usePolling, useServerList } from '../../../hooks/useServerList';
import DspmNotReady from '../DspmNotReady';
import { MAX_SEARCH } from '../datastores/datastoreFilters';
import { DrawerTabList } from '../datastores/drawer/DrawerTabList';
import { ErrorNote, LoadingNote, useHiddenColumns } from '../datastores/drawer/TabParts';
import { ColumnsMenu } from '../datastores/ui/ColumnsMenu';
import { SearchInput } from '../datastores/ui/SearchInput';
import { SelectFilter } from '../datastores/ui/SelectFilter';
import { TableFooter, listPageLimit } from '../datastores/ui/TableFooter';
import RemediationCards from './RemediationCards';
import RemediationRunDrawer from './RemediationRunDrawer';
import RemediationTable, { REMEDIATION_COLUMNS, type RemediationColumnId } from './RemediationTable';
import {
  REMEDIATION_ACTION_OPTIONS, REMEDIATION_STATUS_OPTIONS, isPending, nextStatusFilter,
} from './remediationModel';

/** The console's tabs are Flows and Activity; Flows has no public route (GAP-70), so only Activity shows. */
const TABS = [{ id: 'activity' as const, label: 'Activity' }];

const DEFAULT_PAGE_SIZE = 10;

/** Whether the latest answer still holds the run the drawer shows. */
function holds(data: RemediationActivity, run: RemediationItem): boolean {
  return run.id !== null ? data.items.some(item => item.id === run.id) : data.items.includes(run);
}

const Activity: React.FC = () => {
  const [search, setSearch] = useState('');
  const q = useDebouncedText(search);
  const [status, setStatus] = useState('');
  const [actionType, setActionType] = useState('');
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [analytics, setAnalytics] = useState(true);
  const [hidden, toggleColumn] = useHiddenColumns<RemediationColumnId>();
  const [openRun, setOpenRun] = useState<RemediationItem | null>(null);
  const closeRun = useCallback(() => setOpenRun(null), []);

  // The page belongs to one search, filter and page size: any change of them reads page 1, in the same
  // render, so no request for the old page goes out.
  const filterKey = `${q}\u0000${status}\u0000${actionType}\u0000${pageSize}`;
  const [paging, setPaging] = useState({ key: filterKey, page: 1 });
  const page = paging.key === filterKey ? paging.page : 1;
  const setPage = useCallback((next: number) => setPaging({ key: filterKey, page: next }), [filterKey]);

  const url = pagesApi.remediationUrl({
    q: q === '' ? null : q,
    status: status === '' ? null : status,
    actionType: actionType === '' ? null : actionType,
    skip: (page - 1) * pageSize,
    limit: pageSize,
  });
  const list = useServerList<RemediationActivity>(url);
  const data = list.data;
  usePolling(data !== null && data.items.some(isPending), list.reload);

  // Rows removed between polls can leave this page past the end: go back to the last page.
  useEffect(() => {
    if (data && data.items.length === 0 && data.total > 0 && page > 1) {
      setPage(Math.max(1, Math.ceil(data.total / pageSize)));
    }
    // Only a new answer can show that the page ran past the end.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data]);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <SearchInput value={search} onChange={setSearch} placeholder="Search" maxLength={MAX_SEARCH} className="w-60" />
        <SelectFilter label="Status" value={status} options={REMEDIATION_STATUS_OPTIONS} onChange={setStatus} />
        <SelectFilter label="Action" value={actionType} options={REMEDIATION_ACTION_OPTIONS} onChange={setActionType} />
        <div className="ml-auto flex items-center gap-3">
          <span aria-hidden="true" className="h-6 w-px bg-[rgba(0,0,0,0.1)]" />
          <button
            type="button"
            aria-pressed={analytics}
            aria-label="Show analytics"
            title="Show analytics"
            onClick={() => setAnalytics(on => !on)}
            className={`inline-flex items-center rounded-lg border p-1.5 transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)] ${
              analytics
                ? 'border-sky-300 bg-sky-50 text-sky-700'
                : 'border-[rgba(0,0,0,0.12)] bg-white text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.03)]'
            }`}
          >
            <Icon icon="mdi:chart-box-outline" width={16} />
          </button>
        </div>
      </div>
      {list.error && <ErrorNote error={list.error} onRetry={list.reload} subject="item" />}
      {!list.error && data === null && <LoadingNote />}
      {!list.error && data !== null && (
        <>
          {analytics && (
            <RemediationCards
              summary={data.summary}
              statusCounts={data.statusCounts}
              status={status}
              onSelect={clicked => setStatus(current => nextStatusFilter(current, clicked))}
            />
          )}
          <div className="rounded-2xl border border-[rgba(0,0,0,0.08)] bg-white">
            <div className="flex items-center px-4 py-3">
              <ColumnsMenu columns={REMEDIATION_COLUMNS} hidden={hidden} onToggle={toggleColumn} />
            </div>
            <RemediationTable items={data.items} hidden={hidden} onOpen={setOpenRun} />
            <TableFooter
              page={page}
              pageSize={pageSize}
              total={data.total}
              onPage={setPage}
              onPageSize={setPageSize}
              maxPage={listPageLimit(pageSize)}
            />
          </div>
        </>
      )}
      {openRun && <RemediationRunDrawer item={openRun} missing={data !== null && !holds(data, openRun)} onClose={closeRun} />}
    </div>
  );
};

const ReadyRemediation: React.FC = () => {
  const ids = useId();
  return (
    <div className="space-y-5">
      <h1 className="text-lg font-bold text-[rgba(0,0,0,0.88)]">Remediation</h1>
      <DrawerTabList tabs={TABS} active="activity" onSelect={() => undefined} label="Remediation" idPrefix={ids} />
      <div role="tabpanel" id={`${ids}-panel`} aria-labelledby={`${ids}-tab-activity`}>
        <Activity />
      </div>
    </div>
  );
};

interface Props {
  status: DspmStatus;
  onRetry: () => void;
}

/**
 * XamLens Remediation (MANAGEMENT; design 6.7): the Activity tab over
 * `GET /api/dspm/remediation/activity`, searched (`q`), filtered by Status and Action and paged by the
 * server. The analytics cards filter by status; the list refreshes every minute while a shown run is
 * pending; a row opens its run drawer, whose actions are the console's and greyed.
 */
const RemediationPage: React.FC<Props> = ({ status, onRetry }) => {
  if (!status.ready) return <DspmNotReady status={status} onRetry={onRetry} />;
  return <ReadyRemediation />;
};

export default RemediationPage;
