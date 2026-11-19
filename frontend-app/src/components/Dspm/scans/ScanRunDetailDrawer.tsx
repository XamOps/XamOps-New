import React, { useState } from 'react';
import type { DspmPage } from '../../../types/dspm';
import type { ScanRun, ScanRunDatastore, ScanRunDetail } from '../../../types/dspmScans';
import { scanUrls } from '../../../hooks/useDspmScans';
import { useDebouncedText, useServerList } from '../../../hooks/useServerList';
import { DspmProviderIcon } from '../DspmIcons';
import { MAX_SEARCH } from '../datastores/datastoreFilters';
import { formatListDate } from '../datastores/datastoreFormat';
import { driveIconKey } from '../datastores/datastoreVisuals';
import { NestedDrawerShell } from '../datastores/drawer/DrawerShell';
import { ErrorNote, LoadingNote, TH } from '../datastores/drawer/TabParts';
import { ConsoleOnlyButton } from '../datastores/ui/ConsoleOnly';
import { EmptyState } from '../datastores/ui/EmptyState';
import { SearchInput } from '../datastores/ui/SearchInput';
import { SelectFilter } from '../datastores/ui/SelectFilter';
import { TableFooter, listPageLimit } from '../datastores/ui/TableFooter';
import { RUN_STATUS_OPTIONS, durationText, failedTitle } from './scanFormat';
import {
  DEFAULT_PAGE_SIZE, Dash, DetailField, DetailHeader, Muted, ScanStatusChip, TD, orNull, useFilteredPage,
} from './ScanParts';

const time = (seconds: number | null) => (seconds === null ? '-' : formatListDate(seconds));

/** The run's datastores (S5): Search, Account and Scan Status filters, Export greyed, server paging. */
const RunDatastores: React.FC<{ runId: string; endpoint: boolean; accounts: ScanRunDetail['accounts'] }> = ({
  runId, endpoint, accounts,
}) => {
  const [search, setSearch] = useState('');
  const q = useDebouncedText(search);
  const [account, setAccount] = useState('');
  const [status, setStatus] = useState('');
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [page, setPage] = useFilteredPage([q, account, status, pageSize].join('\u0000'));
  const list = useServerList<DspmPage<ScanRunDatastore>>(scanUrls.runDatastores(runId, {
    q: orNull(q), account: orNull(account), status: orNull(status), page, pageSize,
  }));
  const accountOptions = accounts
    .filter((facet): facet is { value: string; count: number | null } => facet.value !== null && facet.value !== '')
    .map(facet => ({ value: facet.value, label: facet.value }));
  const filtered = q !== '' || account !== '' || status !== '';

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <SearchInput value={search} onChange={setSearch} placeholder={endpoint ? 'Search devices' : 'Search datastores'} maxLength={MAX_SEARCH} className="w-52" />
        <SelectFilter label="Account" value={account} options={accountOptions} onChange={setAccount} />
        <SelectFilter label="Scan Status" value={status} options={RUN_STATUS_OPTIONS} onChange={setStatus} />
        <div className="ml-auto">
          <ConsoleOnlyButton variant="secondary" icon="mdi:download" placement="left">Export</ConsoleOnlyButton>
        </div>
      </div>
      {list.error && <ErrorNote error={list.error} onRetry={list.reload} subject="item" />}
      {!list.error && list.data === null && <LoadingNote />}
      {!list.error && list.data !== null && (
        <div className="rounded-xl border border-[rgba(0,0,0,0.08)]">
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr>
                  <th className={TH}>{endpoint ? 'Device' : 'Datastore'}</th>
                  <th className={TH}>Source / Account</th>
                  <th className={TH}>Scan Status</th>
                </tr>
              </thead>
              <tbody>
                {list.data.data.length === 0 && (
                  <tr>
                    <td colSpan={3}>
                      <EmptyState title={filtered ? 'No datastores match these filters' : 'No datastores in this scan'} />
                    </td>
                  </tr>
                )}
                {list.data.data.map((datastore, i) => (
                  <tr key={datastore.id ?? `datastore-${i}`} className="border-t border-[rgba(0,0,0,0.06)]">
                    <td className={TD}>
                      <span className="flex min-w-0 items-center gap-1.5">
                        <DspmProviderIcon provider={driveIconKey(datastore.subtype)} size={16} />
                        <span className="min-w-0">
                          <span className="block truncate text-[rgba(0,0,0,0.85)]" title={datastore.name ?? undefined}>{datastore.name ?? '-'}</span>
                          {datastore.subtypeLabel && <Muted text={datastore.subtypeLabel} />}
                        </span>
                      </span>
                    </td>
                    <td className={TD}>
                      <span className="block truncate">{datastore.accountName ?? '-'}</span>
                      {datastore.region && <Muted text={datastore.region} />}
                    </td>
                    <td className={TD}>
                      <span className="flex min-w-0 flex-col gap-0.5">
                        <ScanStatusChip status={datastore.status} label={datastore.statusLabel} />
                        {datastore.failureReason && (
                          <span className="block max-w-[20rem] text-[11px] text-rose-600">{datastore.failureReason}</span>
                        )}
                      </span>
                    </td>
                  </tr>
                ))}
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
    </div>
  );
};

interface Props {
  /** The list row; its fields show until the detail arrives. */
  run: ScanRun & { id: string };
  returnFocus: HTMLElement;
  /** Must be stable (`useCallback`). */
  onClose: () => void;
}

/**
 * "Scan History Details" (FR-SM-7; S4 and S5): the run's status, scopes, start, end and duration, then
 * its datastores (devices for an endpoint run) with each one's status and failure reason. Read only.
 */
const ScanRunDetailDrawer: React.FC<Props> = ({ run: row, returnFocus, onClose }) => {
  const detail = useServerList<ScanRunDetail>(scanUrls.run(row.id));
  const run = detail.data?.run ?? row;
  return (
    <NestedDrawerShell label="Scan History Details" returnFocus={returnFocus} onClose={onClose}>
      {({ closeButton, close }) => (
        <>
          <DetailHeader title="Scan History Details" subtitle={run.name} closeButton={closeButton} onClose={close} />
          <div className="flex-1 space-y-4 overflow-y-auto px-5 py-4">
            {detail.error ? <ErrorNote error={detail.error} onRetry={detail.reload} subject="item" /> : (
              <>
                <div className="space-y-3 rounded-xl border border-[rgba(0,0,0,0.08)] bg-white p-4">
                  <DetailField label="Scan Status">
                    <ScanStatusChip status={run.status} label={run.statusLabel} title={failedTitle(run)} />
                  </DetailField>
                  <DetailField label="Scopes">
                    {detail.data === null ? <Dash /> : detail.data.scopeNames.length > 0 ? detail.data.scopeNames.join(', ') : '-'}
                  </DetailField>
                  <DetailField label="Started">{time(run.startedAt)}</DetailField>
                  <DetailField label="Ended">{time(run.endedAt)}</DetailField>
                  <DetailField label="Duration">{durationText(run.durationSeconds)}</DetailField>
                </div>
                {detail.data === null
                  ? <LoadingNote />
                  : <RunDatastores runId={row.id} endpoint={run.platform === 'endpoint'} accounts={detail.data.accounts} />}
              </>
            )}
          </div>
        </>
      )}
    </NestedDrawerShell>
  );
};

export default ScanRunDetailDrawer;
