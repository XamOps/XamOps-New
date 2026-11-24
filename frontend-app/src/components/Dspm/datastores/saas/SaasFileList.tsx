import React, { useEffect, useState } from 'react';
import { Icon } from '@iconify/react';
import type { DspmError } from '../../../../types/dspm';
import type { SaasFilePage, SaasFileRow } from '../../../../types/dspmDrawers';
import type { SaasDrawerApi } from '../../../../hooks/useDspmSaasDrawer';
import { SEARCH_DEBOUNCE_MS, toDspmError } from '../../../../hooks/useDspmDatastores';
import { DspmObjectIcon } from '../../DspmIcons';
import { MAX_SEARCH, normalizeSearch } from '../datastoreFilters';
import { abbreviate, extensionOf } from '../datastoreFormat';
import { SearchInput } from '../ui/SearchInput';
import { TableFooter, listPageLimit } from '../ui/TableFooter';
import { EmptyTableRow, ErrorNote, LoadingNote, TH } from '../drawer/TabParts';
import { fileExtensionLabel } from './saasFormat';

/** Which tab the file drawer opens on: Sensitives from the row, Lineage from its lineage icon. */
export type SaasFileOpenTab = 'sensitives' | 'lineage';

/** Opens the file drawer. `opener` is always the file's row, which takes focus back on close. */
export type OpenSaasFile = (file: SaasFileRow, tab: SaasFileOpenTab, opener: HTMLElement) => void;

interface Props {
  api: SaasDrawerApi;
  /** The expanded row's view: a data element or a document class. */
  kind: 'dataElement' | 'documentClass';
  /** The data element (`EMAIL ADDRESS`) or document class label (`Incident Reports`). */
  value: string;
  onOpenFile: OpenSaasFile;
}

/** The console's inner Rows Per Page: 5 first (DA2). */
const PAGE_SIZES = [5, 10, 25, 50] as const;

const TD = 'px-3 py-2 align-middle text-xs text-[rgba(0,0,0,0.75)]';

/**
 * The files of an expanded Sensitives row (FR-SAAS-6): "File | Records", paged and searched on the server
 * (`/saas/files`, Rows Per Page 5). The search is sent 300 ms after typing stops and returns to page 1.
 * Answers that arrive after a newer request started are ignored, and a page past the end (the data changed
 * since the pager was drawn) returns to page 1.
 *
 * A row with a file id opens the file drawer on click or on Enter pressed on the row itself; its lineage
 * icon opens it on Lineage. A row whose vendor id is not 24 hex is listed but opens nothing.
 */
const SaasFileList: React.FC<Props> = ({ api, kind, value, onOpenFile }) => {
  const [search, setSearch] = useState('');
  const [q, setQ] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState<number>(PAGE_SIZES[0]);
  const [result, setResult] = useState<SaasFilePage | null>(null);
  const [error, setError] = useState<DspmError | null>(null);
  const [attempt, setAttempt] = useState(0);
  const skip = (page - 1) * pageSize;

  useEffect(() => {
    const next = normalizeSearch(search);
    if (next === q) return;
    const timer = window.setTimeout(() => {
      setQ(next);
      setPage(1);
    }, SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [search, q]);

  useEffect(() => {
    let current = true;
    setError(null);
    const query = kind === 'dataElement'
      ? { dataElement: value, q, skip, limit: pageSize }
      : { documentClass: value, q, skip, limit: pageSize };
    api.files(query).then(
      next => {
        if (!current) return;
        if (skip > 0 && skip >= next.total) {
          setPage(1);   // past the end: the data changed since the pager was drawn
          return;
        }
        setResult(next);
      },
      err => { if (current) setError(toDspmError(err)); },
    );
    return () => { current = false; };
  }, [api, kind, value, q, skip, pageSize, attempt]);

  const open = (file: SaasFileRow, tab: SaasFileOpenTab, opener: HTMLElement) => {
    if (file.id) onOpenFile(file, tab, opener);
  };

  return (
    <div data-file-list={value} className="rounded-lg border border-[rgba(0,0,0,0.06)] bg-white">
      <div className="border-b border-[rgba(0,0,0,0.06)] px-3 py-2">
        <SearchInput value={search} onChange={setSearch} placeholder="Search files" maxLength={MAX_SEARCH} className="w-60" />
      </div>
      {error && <div className="p-3"><ErrorNote error={error} onRetry={() => setAttempt(n => n + 1)} /></div>}
      {!error && !result && <LoadingNote />}
      {!error && result && (
        <>
          <div className="overflow-x-auto">
            <table className="w-full text-left">
              <thead>
                <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                  <th scope="col" className={TH}>File</th>
                  <th scope="col" className={TH}>Records</th>
                  <th scope="col" className="w-10 px-3 py-2.5"><span className="sr-only">Lineage</span></th>
                </tr>
              </thead>
              <tbody>
                {result.rows.length === 0 && <EmptyTableRow colSpan={3} searching={q !== null} />}
                {result.rows.map((file, index) => {
                  const openable = file.id !== null;
                  const label = fileExtensionLabel(file.name);
                  return (
                    <tr
                      key={file.id ?? `row-${skip + index}`}
                      tabIndex={openable ? 0 : undefined}
                      onClick={openable ? e => open(file, 'sensitives', e.currentTarget) : undefined}
                      onKeyDown={openable ? (e => {
                        if (e.key === 'Enter' && e.target === e.currentTarget) {
                          e.preventDefault();
                          open(file, 'sensitives', e.currentTarget);
                        }
                      }) : undefined}
                      className={`border-b border-[rgba(0,0,0,0.04)] last:border-b-0 ${
                        openable ? 'cursor-pointer hover:bg-[rgba(0,0,0,0.02)] focus:outline-none focus-visible:bg-[rgba(0,0,0,0.03)]' : ''
                      }`}
                    >
                      <td className={TD}>
                        <span className="flex min-w-0 items-center gap-2">
                          <DspmObjectIcon objectType={null} extension={extensionOf(file.name)} />
                          <span className="min-w-0">
                            <span className="block max-w-[18rem] truncate font-semibold text-[rgba(0,0,0,0.85)]" title={file.name ?? undefined}>
                              {file.name ?? '-'}
                            </span>
                            {label && <span className="block text-[10px] font-semibold text-[rgba(0,0,0,0.4)]">{label}</span>}
                          </span>
                        </span>
                      </td>
                      <td className={TD}>
                        <span className="font-semibold tabular-nums" title={file.records.toLocaleString('en-US')}>{abbreviate(file.records)}</span>
                      </td>
                      <td className={`${TD} text-right`}>
                        {openable && (
                          <button
                            type="button"
                            aria-label={`Open lineage of ${file.name ?? 'this file'}`}
                            onClick={e => {
                              e.stopPropagation();   // the row opens the drawer on Sensitives
                              open(file, 'lineage', e.currentTarget.closest<HTMLElement>('tr') ?? e.currentTarget);
                            }}
                            className="inline-flex items-center rounded-md p-1 text-[rgba(0,0,0,0.5)] transition-colors hover:bg-[rgba(0,0,0,0.05)] hover:text-[rgba(0,0,0,0.85)]"
                          >
                            <Icon icon="mdi:graph-outline" width={14} />
                          </button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <TableFooter
            page={page}
            pageSize={pageSize}
            total={result.total}
            onPage={setPage}
            onPageSize={size => { setPageSize(size); setPage(1); }}
            pageSizes={PAGE_SIZES}
            maxPage={listPageLimit(pageSize)}
          />
        </>
      )}
    </div>
  );
};

export default SaasFileList;
