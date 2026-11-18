import React, { useCallback, useMemo, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import type { SaasFileCopies, SaasFileCopy } from '../../../../types/dspmDrawers';
import type { LazyResource } from '../../../../hooks/useDspmDatastoreDrawer';
import { DspmObjectIcon, DspmProviderIcon } from '../../DspmIcons';
import { usePaged } from '../../dspmShared';
import { MAX_SEARCH } from '../datastoreFilters';
import { sourceIconKey } from '../datastoreVisuals';
import { ColumnsMenu } from '../ui/ColumnsMenu';
import { ConsoleOnlyButton } from '../ui/ConsoleOnly';
import { EmptyState } from '../ui/EmptyState';
import { FullscreenFrame } from '../ui/FullscreenFrame';
import { SearchInput } from '../ui/SearchInput';
import { TableFooter } from '../ui/TableFooter';
import { ResourceView, TH, useHiddenColumns } from '../drawer/TabParts';

type CopyColumnId = 'file' | 'path' | 'datastore' | 'user';

/**
 * Table order; File is always shown. The console's "Columns 5" has a fifth column that is unseen, so the
 * four seen ones are built ("Columns 4", A27 [O3]).
 */
const COLUMNS: ReadonlyArray<{ id: CopyColumnId; label: string }> = [
  { id: 'file', label: 'File' },
  { id: 'path', label: 'File Path' },
  { id: 'datastore', label: 'Datastore' },
  { id: 'user', label: 'User/Device Name' },
];

const TD = 'px-3 py-2.5 align-middle text-xs text-[rgba(0,0,0,0.75)]';
const MUTED = 'text-[11px] text-[rgba(0,0,0,0.45)]';

function renderCell(column: CopyColumnId, copy: SaasFileCopy): React.ReactNode {
  switch (column) {
    case 'file':
      return (
        <span className="flex min-w-0 items-center gap-2">
          <DspmObjectIcon objectType={null} extension={copy.extension} />
          <span className="min-w-0">
            <span className="block max-w-[14rem] truncate font-semibold text-[rgba(0,0,0,0.85)]" title={copy.fileName ?? undefined}>
              {copy.fileName ?? '-'}
            </span>
            {copy.extension && <span className={`block ${MUTED}`}>{copy.extension.toUpperCase()}</span>}
          </span>
        </span>
      );
    case 'path':
      return <span className="block max-w-[14rem] truncate" title={copy.path ?? undefined}>{copy.path ?? '-'}</span>;
    case 'datastore':
      return (
        <span className="flex min-w-0 items-center gap-2">
          <DspmProviderIcon provider={sourceIconKey(copy)} size={16} />
          <span className="min-w-0">
            <span className="block max-w-[12rem] truncate" title={copy.ownerName ?? undefined}>{copy.ownerName ?? '-'}</span>
            {copy.locationLabel && <span className={`block ${MUTED}`}>{copy.locationLabel}</span>}
          </span>
        </span>
      );
    case 'user':
      return <span className="block max-w-[14rem] truncate" title={copy.userOrDevice ?? undefined}>{copy.userOrDevice ?? '-'}</span>;
  }
}

const SummaryCard: React.FC<{ icon: string; label: string; value: number }> = ({ icon, label, value }) => (
  <div className="flex flex-1 items-center gap-3 rounded-xl border border-[rgba(0,0,0,0.08)] px-4 py-3">
    <Icon icon={icon} width={18} className="text-[rgba(0,0,0,0.45)]" />
    <dl>
      <dt className={MUTED}>{label}</dt>
      <dd className="text-lg font-bold tabular-nums text-[rgba(0,0,0,0.88)]">{value.toLocaleString('en-US')}</dd>
    </dl>
  </div>
);

interface ViewProps {
  data: SaasFileCopies;
  search: string;
  onSearch: (value: string) => void;
  hidden: ReadonlySet<CopyColumnId>;
  onToggleColumn: (id: CopyColumnId) => void;
  fullscreen: boolean;
  onFullscreen: () => void;
  toggleRef: React.RefObject<HTMLButtonElement | null>;
}

const CopiesView: React.FC<ViewProps> = ({
  data, search, onSearch, hidden, onToggleColumn, fullscreen, onFullscreen, toggleRef,
}) => {
  const needle = search.trim().toLowerCase();
  const visible = useMemo(
    () => (needle
      ? data.copies.filter(copy => [copy.fileName, copy.path, copy.ownerName, copy.userOrDevice]
        .some(text => text?.toLowerCase().includes(needle)))
      : data.copies),
    [data.copies, needle],
  );
  const paged = usePaged(visible, needle);
  const columns = COLUMNS.filter((column, index) => index === 0 || !hidden.has(column.id));

  return (
    <div className="space-y-3">
      <div className="flex gap-3">
        <SummaryCard icon="mdi:content-copy" label="Copies Found" value={data.copies.length} />
        <SummaryCard icon="mdi:database-outline" label="Locations" value={data.locations} />
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <SearchInput value={search} onChange={onSearch} placeholder="Search" maxLength={MAX_SEARCH} className="w-60" />
        <Icon icon="mdi:filter-variant" width={16} className="text-[rgba(0,0,0,0.45)]" />
        <ConsoleOnlyButton icon="mdi:plus">More filters</ConsoleOnlyButton>
        <div className="ml-auto flex items-center gap-1">
          <ColumnsMenu columns={COLUMNS} hidden={hidden} onToggle={onToggleColumn} />
          {!fullscreen && (
            <button
              ref={toggleRef}
              type="button"
              aria-label="Full screen"
              onClick={onFullscreen}
              className="inline-flex items-center rounded-md p-1 text-[rgba(0,0,0,0.55)] transition-colors hover:bg-[rgba(0,0,0,0.05)] hover:text-[rgba(0,0,0,0.85)]"
            >
              <Icon icon="mdi:fullscreen" width={16} />
            </button>
          )}
        </div>
      </div>
      <div className="overflow-hidden rounded-xl border border-[rgba(0,0,0,0.08)]">
        <div className="overflow-x-auto">
          <table className="w-full text-left">
            <thead>
              <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                {columns.map(column => <th key={column.id} scope="col" className={TH}>{column.label}</th>)}
              </tr>
            </thead>
            <tbody>
              {visible.length === 0 && (
                <tr>
                  <td colSpan={columns.length}>
                    {data.copies.length === 0
                      ? <EmptyState title="No copies found" />
                      : <EmptyState title="No matches" body="Try a different search." />}
                  </td>
                </tr>
              )}
              {paged.items.map(copy => (
                <tr key={copy.key} className="border-b border-[rgba(0,0,0,0.04)] last:border-b-0">
                  {columns.map(column => <td key={column.id} className={TD}>{renderCell(column.id, copy)}</td>)}
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

/**
 * The File Copies tab of a Google Drive file (FR-SFILE-10, D11): the copies of the file's content other
 * than the file itself, with the Copies Found and Locations cards, a search over file name, path, owner
 * and user, the greyed "+ More filters" (A27) and a full-screen view. The search and the hidden columns
 * live above the full-screen frame, so they survive it.
 */
const SaasFileCopiesTab: React.FC<{ copies: LazyResource<SaasFileCopies> }> = ({ copies }) => {
  const [search, setSearch] = useState('');
  const [hidden, toggleColumn] = useHiddenColumns<CopyColumnId>();
  const [fullscreen, setFullscreen] = useState(false);
  const toggle = useRef<HTMLButtonElement>(null);
  const exitFullscreen = useCallback(() => setFullscreen(false), []);

  return (
    <ResourceView resource={copies} subject="file">
      {data => (
        <FullscreenFrame open={fullscreen} title="File copies" onExit={exitFullscreen} toggleRef={toggle}>
          <CopiesView
            data={data}
            search={search}
            onSearch={setSearch}
            hidden={hidden}
            onToggleColumn={toggleColumn}
            fullscreen={fullscreen}
            onFullscreen={() => setFullscreen(true)}
            toggleRef={toggle}
          />
        </FullscreenFrame>
      )}
    </ResourceView>
  );
};

export default SaasFileCopiesTab;
