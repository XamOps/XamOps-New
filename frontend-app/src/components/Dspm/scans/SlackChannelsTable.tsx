import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import type { DspmPage } from '../../../types/dspm';
import type { SaasChannel } from '../../../types/dspmScans';
import { scanUrls } from '../../../hooks/useDspmScans';
import { useDebouncedText, useServerList } from '../../../hooks/useServerList';
import { MAX_SEARCH } from '../datastores/datastoreFilters';
import { ErrorNote, LoadingNote, TH } from '../datastores/drawer/TabParts';
import { ConsoleOnlySwitch } from '../datastores/ui/ConsoleOnly';
import { EmptyState } from '../datastores/ui/EmptyState';
import { SearchInput } from '../datastores/ui/SearchInput';
import { SelectFilter } from '../datastores/ui/SelectFilter';
import { TableFooter, listPageLimit } from '../datastores/ui/TableFooter';
import {
  CHANNEL_NEW_OPTIONS, CHANNEL_SCAN_OPTIONS, CHANNEL_STATUS_OPTIONS, CHANNEL_TYPE_OPTIONS, prettifyCode,
} from './scanFormat';
import { DEFAULT_PAGE_SIZE, Dash, Muted, TD, orNull, useFilteredPage } from './ScanParts';

const Tag: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <span className="inline-flex items-center rounded border border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.03)] px-1 text-[10px] font-semibold leading-4 text-[rgba(0,0,0,0.6)]">
    {children}
  </span>
);

const ChannelCell: React.FC<{ channel: SaasChannel }> = ({ channel }) => (
  <span className="block min-w-0 max-w-[22rem]">
    <span className="flex min-w-0 items-center gap-1.5">
      <span role="img" aria-label={channel.channelType === 'PRIVATE' ? 'Private channel' : 'Public channel'} className="inline-flex flex-shrink-0 text-[rgba(0,0,0,0.5)]">
        {channel.channelType === 'PRIVATE'
          ? <Icon icon="mdi:lock-outline" width={13} />
          : <Icon icon="mdi:pound" width={13} />}
      </span>
      <span className="truncate font-semibold text-[rgba(0,0,0,0.85)]" title={channel.name ?? undefined}>{channel.name ?? '-'}</span>
      {channel.isNew && <Tag>New</Tag>}
      {channel.externalShared && <Tag>External</Tag>}
    </span>
    {channel.topic && <Muted text={channel.topic} />}
  </span>
);

/**
 * The Slack channels of an integration whose Slack scope is Selected (FR-SM-3; S11): Search, Channel
 * Type, Channel Status, Scan and New filters (the bundle's codes: PUBLIC / PRIVATE, ACTIVE / ARCHIVED,
 * ENABLED / DISABLED, and "New only" sending `isNew=true`), server paging, and a greyed Scan switch.
 */
const SlackChannelsTable: React.FC<{ integrationId: string }> = ({ integrationId }) => {
  const [search, setSearch] = useState('');
  const q = useDebouncedText(search);
  const [channelType, setChannelType] = useState('');
  const [channelStatus, setChannelStatus] = useState('');
  const [scanState, setScanState] = useState('');
  const [isNew, setIsNew] = useState('');
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [page, setPage] = useFilteredPage([q, channelType, channelStatus, scanState, isNew, pageSize].join('\u0000'));

  const list = useServerList<DspmPage<SaasChannel>>(scanUrls.saasChannels(integrationId, {
    q: orNull(q),
    channelType: orNull(channelType),
    channelStatus: orNull(channelStatus),
    scanState: orNull(scanState),
    isNew: isNew === 'true' ? true : null,
    page,
    pageSize,
  }));
  const filtered = q !== '' || channelType !== '' || channelStatus !== '' || scanState !== '' || isNew !== '';

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <SearchInput value={search} onChange={setSearch} placeholder="Search channels" maxLength={MAX_SEARCH} className="w-56" />
        <SelectFilter label="Channel Type" value={channelType} options={CHANNEL_TYPE_OPTIONS} onChange={setChannelType} />
        <SelectFilter label="Channel Status" value={channelStatus} options={CHANNEL_STATUS_OPTIONS} onChange={setChannelStatus} />
        <SelectFilter label="Scan" value={scanState} options={CHANNEL_SCAN_OPTIONS} onChange={setScanState} />
        <SelectFilter label="New" value={isNew} options={CHANNEL_NEW_OPTIONS} onChange={setIsNew} />
      </div>
      {list.error && <ErrorNote error={list.error} onRetry={list.reload} subject="item" />}
      {!list.error && list.data === null && <LoadingNote />}
      {!list.error && list.data !== null && (
        <div className="rounded-xl border border-[rgba(0,0,0,0.08)]">
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr>
                  <th className={TH}>Channel</th>
                  <th className={TH}>Type</th>
                  <th className={TH}>Status</th>
                  <th className={TH}>Members</th>
                  <th className={TH}>Scan</th>
                </tr>
              </thead>
              <tbody>
                {list.data.data.length === 0 && (
                  <tr>
                    <td colSpan={5}>
                      {filtered
                        ? <EmptyState title="No channels match these filters" body="Try clearing a filter or widening the search." />
                        : <EmptyState title="No channels found" />}
                    </td>
                  </tr>
                )}
                {list.data.data.map((channel, i) => (
                  <tr key={channel.id ?? `channel-${i}`} className="border-t border-[rgba(0,0,0,0.06)]">
                    <td className={TD}><ChannelCell channel={channel} /></td>
                    <td className={TD}>{prettifyCode(channel.channelType) ?? <Dash />}</td>
                    <td className={TD}>{prettifyCode(channel.channelStatus) ?? <Dash />}</td>
                    <td className={TD}>{channel.memberCount === null ? <Dash /> : channel.memberCount.toLocaleString('en-US')}</td>
                    <td className={TD}>
                      <ConsoleOnlySwitch label={`Scan ${channel.name ?? 'channel'}`} checked={channel.selected} placement="left" />
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

export default SlackChannelsTable;
