import React, { useCallback, useState } from 'react';
import type { DspmPage } from '../../../types/dspm';
import type { ScanSchedule } from '../../../types/dspmScans';
import { scanUrls } from '../../../hooks/useDspmScans';
import { useDebouncedText, useServerList } from '../../../hooks/useServerList';
import { MAX_SEARCH } from '../datastores/datastoreFilters';
import { formatListDate } from '../datastores/datastoreFormat';
import { ErrorNote, LoadingNote, TH } from '../datastores/drawer/TabParts';
import { ConsoleOnlyButton } from '../datastores/ui/ConsoleOnly';
import { EmptyState } from '../datastores/ui/EmptyState';
import { SearchInput } from '../datastores/ui/SearchInput';
import { SelectFilter } from '../datastores/ui/SelectFilter';
import { TableFooter, listPageLimit } from '../datastores/ui/TableFooter';
import { COVERAGE_OPTIONS, PLATFORM_OPTIONS, RUN_STATUS_OPTIONS, scheduleName, targetsText } from './scanFormat';
import {
  DEFAULT_PAGE_SIZE, Dash, FrequencyCell, LastRunCell, Muted, OpenRow, PanelIntro, TD, orNull, scheduleSubtitle, useFilteredPage,
} from './ScanParts';
import ScheduleDetailDrawer from './ScheduleDetailDrawer';

/**
 * Schedules (FR-SM-6; S1): Search, Environment, Scan Status and Coverage over the server list, the
 * console's seven columns and a greyed ⋮ per row. Export and Schedule Scan are the console's and greyed;
 * a row opens "Schedule Details".
 */
const SchedulesTab: React.FC = () => {
  const [search, setSearch] = useState('');
  const q = useDebouncedText(search);
  const [platform, setPlatform] = useState('');
  const [status, setStatus] = useState('');
  const [coverage, setCoverage] = useState('');
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [page, setPage] = useFilteredPage([q, platform, status, coverage, pageSize].join('\u0000'));
  const list = useServerList<DspmPage<ScanSchedule>>(scanUrls.schedules({
    q: orNull(q), platform: orNull(platform), status: orNull(status), coverage: orNull(coverage), page, pageSize,
  }));
  const filtered = q !== '' || platform !== '' || status !== '' || coverage !== '';

  const [open, setOpen] = useState<{ schedule: ScanSchedule & { id: string }; opener: HTMLElement } | null>(null);
  const close = useCallback(() => setOpen(null), []);

  return (
    <div className="space-y-4">
      <PanelIntro text="Create and manage recurring and one-time scans, and see when each last ran." />
      <div className="flex flex-wrap items-center gap-2">
        <SearchInput value={search} onChange={setSearch} placeholder="Search schedules" maxLength={MAX_SEARCH} className="w-56" />
        <SelectFilter label="Environment" value={platform} options={PLATFORM_OPTIONS} onChange={setPlatform} />
        <SelectFilter label="Scan Status" value={status} options={RUN_STATUS_OPTIONS} onChange={setStatus} />
        <SelectFilter label="Coverage" value={coverage} options={COVERAGE_OPTIONS} onChange={setCoverage} />
        <div className="ml-auto flex items-center gap-2">
          <ConsoleOnlyButton variant="secondary" icon="mdi:download">Export</ConsoleOnlyButton>
          <ConsoleOnlyButton variant="primary" icon="mdi:plus">Schedule Scan</ConsoleOnlyButton>
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
                  <th className={TH}>Schedule Name</th>
                  <th className={TH}>Environment</th>
                  <th className={TH}>Coverage</th>
                  <th className={TH}>Targets</th>
                  <th className={TH}>Frequency</th>
                  <th className={TH}>Scheduled Time</th>
                  <th className={TH}>Scan Status</th>
                  <th className={TH}><span className="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {list.data.data.length === 0 && (
                  <tr>
                    <td colSpan={8}>
                      {filtered
                        ? <EmptyState title="No schedules match these filters" />
                        : (
                          <EmptyState
                            title="No scan schedules yet"
                            body="Set up recurring scans to automate discovery and classification across your data sources."
                          />
                        )}
                    </td>
                  </tr>
                )}
                {list.data.data.map((schedule, i) => {
                  const id = schedule.id;
                  const subtitle = scheduleSubtitle(schedule);
                  return (
                    <OpenRow
                      key={id ?? `schedule-${i}`}
                      onOpen={id ? opener => setOpen({ schedule: { ...schedule, id }, opener }) : undefined}
                    >
                      <td className={TD}>
                        <span className="block font-semibold text-[rgba(0,0,0,0.85)]">{scheduleName(schedule.name)}</span>
                        {subtitle && <Muted text={subtitle} />}
                      </td>
                      <td className={TD}>{schedule.platformLabel ?? <Dash />}</td>
                      <td className={TD}>{schedule.coverageLabel ?? <Dash />}</td>
                      <td className={`${TD} whitespace-nowrap`}>{targetsText(schedule.totalDatastores, schedule.totalIsEstimate)}</td>
                      <td className={TD}><FrequencyCell schedule={schedule} /></td>
                      <td className={`${TD} whitespace-nowrap`}>
                        {schedule.timing?.triggerTime != null ? formatListDate(schedule.timing.triggerTime) : <Dash />}
                      </td>
                      <td className={TD}><LastRunCell schedule={schedule} /></td>
                      <td className={`${TD} text-right`}>
                        <ConsoleOnlyButton variant="icon" icon="mdi:dots-vertical" label="Schedule actions" placement="left" />
                      </td>
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
      {open && <ScheduleDetailDrawer schedule={open.schedule} returnFocus={open.opener} onClose={close} />}
    </div>
  );
};

export default SchedulesTab;
