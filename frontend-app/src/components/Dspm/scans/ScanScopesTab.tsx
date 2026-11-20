import React, { useCallback, useState } from 'react';
import type { DspmPage } from '../../../types/dspm';
import type { ScanScope } from '../../../types/dspmScans';
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
import { PLATFORM_OPTIONS, prettifyCode } from './scanFormat';
import { DEFAULT_PAGE_SIZE, Dash, Muted, OpenRow, PanelIntro, TD, orNull, useFilteredPage } from './ScanParts';
import ScopeDetailDrawer from './ScopeDetailDrawer';

/**
 * Scan Scopes (FR-SM-5; S6): Search and Environment over the server list. Create Scope, Export and each
 * row's ⋮ are the console's and greyed; a row opens the scope's detail layer with its targets.
 */
const ScanScopesTab: React.FC = () => {
  const [search, setSearch] = useState('');
  const q = useDebouncedText(search);
  const [platform, setPlatform] = useState('');
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [page, setPage] = useFilteredPage(`${q}\u0000${platform}\u0000${pageSize}`);
  const list = useServerList<DspmPage<ScanScope>>(scanUrls.scopes({ q: orNull(q), platform: orNull(platform), page, pageSize }));
  const filtered = q !== '' || platform !== '';

  const [open, setOpen] = useState<{ scope: ScanScope & { id: string }; opener: HTMLElement } | null>(null);
  const close = useCallback(() => setOpen(null), []);

  return (
    <div className="space-y-4">
      <PanelIntro text="Create a scope to choose which datastores or devices a scan covers, then reuse it in any schedule." />
      <div className="flex flex-wrap items-center gap-2">
        <SearchInput value={search} onChange={setSearch} placeholder="Search scopes" maxLength={MAX_SEARCH} className="w-56" />
        <SelectFilter label="Environment" value={platform} options={PLATFORM_OPTIONS} onChange={setPlatform} />
        <div className="ml-auto flex items-center gap-2">
          <ConsoleOnlyButton variant="secondary" icon="mdi:download">Export</ConsoleOnlyButton>
          <ConsoleOnlyButton variant="primary" icon="mdi:plus">Create Scope</ConsoleOnlyButton>
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
                  <th className={TH}>Scope Name</th>
                  <th className={TH}>Environment</th>
                  <th className={TH}>Targets</th>
                  <th className={TH}>Last Modified</th>
                  <th className={TH}><span className="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {list.data.data.length === 0 && (
                  <tr>
                    <td colSpan={5}>
                      {filtered
                        ? <EmptyState title="No scopes match these filters" />
                        : (
                          <EmptyState
                            title="No scan scopes yet"
                            body="Save a set of datastores or devices as a scope, then point any schedule at it instead of picking them again."
                          />
                        )}
                    </td>
                  </tr>
                )}
                {list.data.data.map((scope, i) => {
                  const id = scope.id;
                  const mode = prettifyCode(scope.scopeMode);
                  return (
                    <OpenRow key={id ?? `scope-${i}`} onOpen={id ? opener => setOpen({ scope: { ...scope, id }, opener }) : undefined}>
                      <td className={TD}>
                        <span className="block font-semibold text-[rgba(0,0,0,0.85)]">{scope.name ?? '-'}</span>
                        {mode && <Muted text={mode} />}
                      </td>
                      <td className={TD}>{scope.platformLabel ?? <Dash />}</td>
                      <td className={TD}>{scope.totalDatastores === null ? <Dash /> : scope.totalDatastores.toLocaleString('en-US')}</td>
                      <td className={`${TD} whitespace-nowrap`}>{scope.updatedAt === null ? <Dash /> : formatListDate(scope.updatedAt)}</td>
                      <td className={`${TD} text-right`}>
                        <ConsoleOnlyButton variant="icon" icon="mdi:dots-vertical" label="Scope actions" placement="left" />
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
      {open && <ScopeDetailDrawer scope={open.scope} returnFocus={open.opener} onClose={close} />}
    </div>
  );
};

export default ScanScopesTab;
