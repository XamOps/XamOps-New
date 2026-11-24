import React, { useCallback, useState } from 'react';
import type { DspmPage } from '../../../types/dspm';
import type { ScanRun } from '../../../types/dspmScans';
import { scanUrls } from '../../../hooks/useDspmScans';
import { useDebouncedText, usePolling, useServerList } from '../../../hooks/useServerList';
import { MAX_SEARCH } from '../datastores/datastoreFilters';
import { formatListDate } from '../datastores/datastoreFormat';
import { ErrorNote, LoadingNote, TH } from '../datastores/drawer/TabParts';
import { ConsoleOnlyButton } from '../datastores/ui/ConsoleOnly';
import { EmptyState } from '../datastores/ui/EmptyState';
import { SearchInput } from '../datastores/ui/SearchInput';
import { SelectFilter } from '../datastores/ui/SelectFilter';
import { TableFooter, listPageLimit } from '../datastores/ui/TableFooter';
import {
  COVERAGE_OPTIONS, PLATFORM_OPTIONS, RUN_STATUS_OPTIONS, TRIGGER_OPTIONS, failedTitle, progressText,
} from './scanFormat';
import { DEFAULT_PAGE_SIZE, Dash, Muted, OpenRow, PanelIntro, ScanStatusChip, TD, orNull, useFilteredPage } from './ScanParts';
import ScanRunDetailDrawer from './ScanRunDetailDrawer';

/** The Progress cell: done / total, the phase and the skipped count. */
export const ProgressCell: React.FC<{ run: ScanRun }> = ({ run }) => {
  const progress = progressText(run);
  return (
    <span className="block whitespace-nowrap">
      <span className="block">{progress.main}</span>
      {progress.phase && <Muted text={progress.phase} />}
      {progress.skipped && <Muted text={progress.skipped} />}
    </span>
  );
};

/**
 * Scan History (FR-SM-7; S3): Search, Coverage, Environment, Scan Status and Trigger over the server
 * list. The list refreshes every minute while a shown run is in progress. Export is the console's and
 * greyed; a row opens "Scan History Details".
 */
const ScanHistoryTab: React.FC = () => {
  const [search, setSearch] = useState('');
  const q = useDebouncedText(search);
  const [coverage, setCoverage] = useState('');
  const [platform, setPlatform] = useState('');
  const [status, setStatus] = useState('');
  const [trigger, setTrigger] = useState('');
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [page, setPage] = useFilteredPage([q, coverage, platform, status, trigger, pageSize].join('\u0000'));
  const list = useServerList<DspmPage<ScanRun>>(scanUrls.runs({
    q: orNull(q), coverage: orNull(coverage), platform: orNull(platform), status: orNull(status), trigger: orNull(trigger),
    page, pageSize,
  }));
  const rows = list.data?.data ?? [];
  usePolling(rows.some(run => run.status === 'IN_PROGRESS'), list.reload);
  const filtered = q !== '' || coverage !== '' || platform !== '' || status !== '' || trigger !== '';

  const [open, setOpen] = useState<{ run: ScanRun & { id: string }; opener: HTMLElement } | null>(null);
  const close = useCallback(() => setOpen(null), []);

  return (
    <div className="space-y-4">
      <PanelIntro text="View the history of all scheduled and immediate scan executions." />
      <div className="flex flex-wrap items-center gap-2">
        <SearchInput value={search} onChange={setSearch} placeholder="Search scans" maxLength={MAX_SEARCH} className="w-56" />
        <SelectFilter label="Coverage" value={coverage} options={COVERAGE_OPTIONS} onChange={setCoverage} />
        <SelectFilter label="Environment" value={platform} options={PLATFORM_OPTIONS} onChange={setPlatform} />
        <SelectFilter label="Scan Status" value={status} options={RUN_STATUS_OPTIONS} onChange={setStatus} />
        <SelectFilter label="Trigger" value={trigger} options={TRIGGER_OPTIONS} onChange={setTrigger} />
        <div className="ml-auto">
          <ConsoleOnlyButton variant="secondary" icon="mdi:download">Export</ConsoleOnlyButton>
        </div>
      </div>
      {list.error && <ErrorNote error={list.error} onRetry={list.reload} subject="item" />}
      {!list.error && list.data === null && <LoadingNote />}
      {!list.error && list.data !== null && (
        <div className="rounded-xl border border-[rgba(0,0,0,0.08)] bg-white">
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr>
                  <th className={TH}>Name</th>
                  <th className={TH}>Coverage</th>
                  <th className={TH}>Environment</th>
                  <th className={TH}>Trigger</th>
                  <th className={TH}>Scheduled Time</th>
                  <th className={TH}>Scan Status</th>
                  <th className={TH}>Progress</th>
                </tr>
              </thead>
              <tbody>
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={7}>
                      {filtered
                        ? <EmptyState title="No scans match these filters" />
                        : <EmptyState title="No scans have run yet" body="Once a scheduled or immediate scan runs, its execution history appears here." />}
                    </td>
                  </tr>
                )}
                {rows.map((run, i) => {
                  const id = run.id;
                  return (
                    <OpenRow key={id ?? `run-${i}`} onOpen={id ? opener => setOpen({ run: { ...run, id }, opener }) : undefined}>
                      <td className={TD}><span className="font-semibold text-[rgba(0,0,0,0.85)]">{run.name ?? '-'}</span></td>
                      <td className={TD}>{run.coverageLabel ?? <Dash />}</td>
                      <td className={TD}>{run.platformLabel ?? <Dash />}</td>
                      <td className={TD}>{run.triggerLabel ?? <Dash />}</td>
                      <td className={`${TD} whitespace-nowrap`}>{run.scheduledTime === null ? <Dash /> : formatListDate(run.scheduledTime)}</td>
                      <td className={TD}><ScanStatusChip status={run.status} label={run.statusLabel} title={failedTitle(run)} /></td>
                      <td className={TD}><ProgressCell run={run} /></td>
                    </OpenRow>
                  );
                })}
              </tbody>
            </table>
          </div>
          <TableFooter
            page={page}
            pageSize={pageSize}
            total={list.data.total}
            onPage={setPage}
            onPageSize={setPageSize}
            maxPage={listPageLimit(pageSize)}
          />
        </div>
      )}
      {open && <ScanRunDetailDrawer run={open.run} returnFocus={open.opener} onClose={close} />}
    </div>
  );
};

export default ScanHistoryTab;
