import React, { useState } from 'react';
import type { DspmPage } from '../../../types/dspm';
import type { SaasScanUser } from '../../../types/dspmScans';
import { scanUrls } from '../../../hooks/useDspmScans';
import { useDebouncedText, useServerList } from '../../../hooks/useServerList';
import { MAX_SEARCH } from '../datastores/datastoreFilters';
import { ErrorNote, LoadingNote, TH } from '../datastores/drawer/TabParts';
import { ConsoleOnlySwitch } from '../datastores/ui/ConsoleOnly';
import { EmptyState } from '../datastores/ui/EmptyState';
import { SearchInput } from '../datastores/ui/SearchInput';
import { SelectFilter } from '../datastores/ui/SelectFilter';
import { TableFooter, listPageLimit } from '../datastores/ui/TableFooter';
import { USER_STATUS_OPTIONS } from './scanFormat';
import { DEFAULT_PAGE_SIZE, Dash, TD, orNull, useFilteredPage } from './ScanParts';

interface Props {
  integrationId: string;
  /** The active drive (required by the endpoint). */
  driveType: string;
  /** The scope's `supportedDomains`; the Domain filter shows only when there are some (the console's rule). */
  domains: string[];
}

/**
 * The users of a drive whose scope is Selected users (FR-SM-3; S10): Search, User Status and Domain
 * filters, server paging, and a greyed Scan switch per user in the state the console reports.
 */
const SaasUsersTable: React.FC<Props> = ({ integrationId, driveType, domains }) => {
  const [search, setSearch] = useState('');
  const q = useDebouncedText(search);
  const [status, setStatus] = useState('');
  const [domain, setDomain] = useState('');
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [page, setPage] = useFilteredPage(`${q}\u0000${status}\u0000${domain}\u0000${pageSize}`);

  const list = useServerList<DspmPage<SaasScanUser>>(scanUrls.saasUsers(integrationId, {
    driveType, q: orNull(q), status: orNull(status), domain: orNull(domain), page, pageSize,
  }));
  const filtered = q !== '' || status !== '' || domain !== '';

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <SearchInput value={search} onChange={setSearch} placeholder="Search users" maxLength={MAX_SEARCH} className="w-56" />
        <SelectFilter label="User Status" value={status} options={USER_STATUS_OPTIONS} onChange={setStatus} />
        {domains.length > 0 && (
          <SelectFilter label="Domain" value={domain} options={domains.map(d => ({ value: d, label: d }))} onChange={setDomain} />
        )}
      </div>
      <p className="text-[11px] text-[rgba(0,0,0,0.5)]">Changes in this table save immediately.</p>
      {list.error && <ErrorNote error={list.error} onRetry={list.reload} subject="item" />}
      {!list.error && list.data === null && <LoadingNote />}
      {!list.error && list.data !== null && (
        <div className="rounded-xl border border-[rgba(0,0,0,0.08)]">
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr>
                  <th className={TH}>User</th>
                  <th className={TH}>User Status</th>
                  <th className={TH}>Scan</th>
                </tr>
              </thead>
              <tbody>
                {list.data.data.length === 0 && (
                  <tr>
                    <td colSpan={3}>
                      {filtered
                        ? <EmptyState title="No users match these filters" body="Try clearing a filter or widening the search." />
                        : <EmptyState title="No users found" />}
                    </td>
                  </tr>
                )}
                {list.data.data.map((user, i) => (
                  <tr key={user.id ?? `user-${i}`} className="border-t border-[rgba(0,0,0,0.06)]">
                    <td className={TD}>{user.email ?? <Dash />}</td>
                    <td className={TD}>{user.statusLabel ?? user.status ?? <Dash />}</td>
                    <td className={TD}>
                      <ConsoleOnlySwitch label={`Scan ${user.email ?? 'user'}`} checked={user.selected} placement="left" />
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

export default SaasUsersTable;
