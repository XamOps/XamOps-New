import React, { useEffect, useState } from 'react';
import type { DspmError } from '../../../../types/dspm';
import type { DatastoreFilePage, DatastoreFileRow } from '../../../../types/dspmDatastores';
import type { DatastoreDrawerApi } from '../../../../hooks/useDspmDatastoreDrawer';
import { DSPM_PAGE_SIZES } from '../../../../hooks/useDspmData';
import { SEARCH_DEBOUNCE_MS, toDspmError } from '../../../../hooks/useDspmDatastores';
import { DspmObjectIcon } from '../../DspmIcons';
import { normalizeSearch } from '../datastoreFilters';
import { abbreviate, extensionOf } from '../datastoreFormat';
import { CategoryChip, EntityChip } from '../ui/Chips';
import { ColumnsMenu } from '../ui/ColumnsMenu';
import { TableFooter, listPageLimit } from '../ui/TableFooter';
import { EmptyTableRow, ErrorNote, LoadingNote, TH, useHiddenColumns } from './TabParts';

type FileColumnId = 'file' | 'path' | 'sensitives' | 'category' | 'documentClass' | 'entities';

/** Table order; File is always shown. */
const FILE_COLUMNS: ReadonlyArray<{ id: FileColumnId; label: string }> = [
  { id: 'file', label: 'File' },
  { id: 'path', label: 'File Path' },
  { id: 'sensitives', label: 'Sensitives' },
  { id: 'category', label: 'Category' },
  { id: 'documentClass', label: 'Document Class' },
  { id: 'entities', label: 'Entities' },
];

const TD = 'px-3 py-2.5 align-middle text-xs text-[rgba(0,0,0,0.75)]';

function renderCell(column: FileColumnId, row: DatastoreFileRow): React.ReactNode {
  switch (column) {
    case 'file':
      return (
        <span className="flex min-w-0 items-center gap-2">
          <DspmObjectIcon objectType={null} extension={extensionOf(row.name)} />
          <span className="max-w-[14rem] truncate font-semibold text-[rgba(0,0,0,0.85)]" title={row.name}>{row.name}</span>
        </span>
      );
    case 'path':
      return <span className="block max-w-[16rem] truncate" title={row.path}>{row.path}</span>;
    case 'sensitives':
      return (
        <span className="flex flex-wrap items-center gap-1.5">
          <span className="font-semibold tabular-nums text-[rgba(0,0,0,0.8)]" title={row.count.toLocaleString('en-US')}>
            {abbreviate(row.count)}
          </span>
          {row.labels.map(label => <CategoryChip key={label} label={label} />)}
        </span>
      );
    // No API field for either (G4).
    case 'category':
    case 'documentClass':
      return <span className="text-[rgba(0,0,0,0.4)]">-</span>;
    case 'entities':
      return (
        <span className="flex flex-wrap items-center gap-1">
          {row.entities.map((entity, index) => (
            <EntityChip key={`${index}:${entity.name}`} name={entity.name} categoryLabel={entity.label} />
          ))}
        </span>
      );
  }
}

/**
 * File View: one row per file the scan read, including files whose only entity is blank (count 0, an
 * empty grey entity chip). Paged and searched by path on the server (`/sensitives/files`), samples never
 * included. The search is sent 300 ms after typing stops.
 *
 * The backend serves offsets up to 10,000, so the pager stops at `listPageLimit` and a note says how
 * far paging reaches when there are more files than that.
 */
const FileView: React.FC<{ api: DatastoreDrawerApi; search: string }> = ({ api, search }) => {
  const [q, setQ] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState<number>(DSPM_PAGE_SIZES[0]);
  const [result, setResult] = useState<DatastoreFilePage | null>(null);
  const [error, setError] = useState<DspmError | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [hidden, toggleColumn] = useHiddenColumns<FileColumnId>();

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
    api.files(page, pageSize, q).then(
      next => { if (current) setResult(next); },
      err => { if (current) setError(toDspmError(err)); },
    );
    return () => { current = false; };
  }, [api, page, pageSize, q, attempt]);

  const columns = FILE_COLUMNS.filter((column, index) => index === 0 || !hidden.has(column.id));
  const maxPage = listPageLimit(pageSize);

  return (
    <div className="space-y-2">
      <div className="overflow-hidden rounded-xl border border-[rgba(0,0,0,0.08)]">
        <header className="flex items-center border-b border-[rgba(0,0,0,0.06)] px-3 py-2">
          <ColumnsMenu columns={FILE_COLUMNS} hidden={hidden} onToggle={toggleColumn} />
        </header>
        {error && <div className="p-3"><ErrorNote error={error} onRetry={() => setAttempt(n => n + 1)} /></div>}
        {!error && !result && <LoadingNote />}
        {!error && result && (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-left">
                <thead>
                  <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                    {columns.map(column => <th key={column.id} scope="col" className={TH}>{column.label}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {result.rows.length === 0 && <EmptyTableRow colSpan={columns.length} searching={q !== null} />}
                  {result.rows.map(row => (
                    <tr key={row.path} className="border-b border-[rgba(0,0,0,0.04)] last:border-b-0">
                      {columns.map(column => <td key={column.id} className={TD}>{renderCell(column.id, row)}</td>)}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <TableFooter
              page={page}
              pageSize={pageSize}
              total={result.total}
              onPage={setPage}
              onPageSize={size => { setPageSize(size); setPage(1); }}
              maxPage={maxPage}
            />
          </>
        )}
      </div>
      {result && !error && result.total > maxPage * pageSize && (
        <p className="text-[11px] text-[rgba(0,0,0,0.45)]">
          Paging covers about the first 10,000 files. Search by path to find others.
        </p>
      )}
      {result && !error && result.truncated && (
        <p className="text-[11px] text-[rgba(0,0,0,0.45)]">Showing the first 50,000 files.</p>
      )}
    </div>
  );
};

export default FileView;
