import React, { useEffect, useState } from 'react';
import type { DspmError } from '../../../../types/dspm';
import type { EndpointFilePage, EndpointFileRow } from '../../../../types/dspmDrawers';
import type { EndpointDrawerApi, EndpointFilterParams } from '../../../../hooks/useDspmEndpointDrawer';
import { DSPM_PAGE_SIZES } from '../../../../hooks/useDspmData';
import { SEARCH_DEBOUNCE_MS, toDspmError } from '../../../../hooks/useDspmDatastores';
import { normalizeSearch } from '../datastoreFilters';
import { abbreviate, formatBytes, formatConsoleDate } from '../datastoreFormat';
import { ErrorNote, LoadingNote, TH, useHiddenColumns } from '../drawer/TabParts';
import { DotChip, LabelChips } from '../ui/Chips';
import { ColumnsMenu } from '../ui/ColumnsMenu';
import { ConsoleOnlyButton } from '../ui/ConsoleOnly';
import { TableFooter, listPageLimit } from '../ui/TableFooter';
import {
  CATEGORY_DOT, DOCUMENT_CLASS_DOT, ENDPOINT_FILE_COLUMNS, type EndpointFileColumnId,
} from './endpointFileColumns';
import { FileNameCell, NoEntitiesState, NoMatchesState, PathCell, TD, TagChips } from './EndpointParts';

interface Props {
  api: EndpointDrawerApi;
  /** Memoised by the caller: a new object means new filters, page 1 and a new request. */
  filters: EndpointFilterParams;
  search: string;
}

/*
 * File Name and the lineage icon stay in place while the other columns scroll (ninth batch): both get
 * the row's background and z-10, so the scrolled cells pass under them. The name cell is 14rem wide
 * (its text truncates inside), which is where the icon column starts.
 */
const STICKY_NAME = 'sticky left-0 z-10 w-56 min-w-[14rem] max-w-[14rem]';
const STICKY_ICON = 'sticky left-56 z-10 w-10';
/** The header row's tint as an opaque colour, so the scrolled header cells do not show through. */
const STICKY_HEAD_BG = 'bg-[#fbfbfb]';

const DASH = <span className="text-[rgba(0,0,0,0.4)]">-</span>;

function renderCell(column: EndpointFileColumnId, row: EndpointFileRow): React.ReactNode {
  switch (column) {
    case 'fileName':
      return <FileNameCell name={row.name} extension={row.extension} />;
    case 'path':
      return <PathCell path={row.path} />;
    case 'user':
      return row.user ?? DASH;
    case 'size':
      return row.sizeBytes === null ? DASH : <span className="whitespace-nowrap">{formatBytes(row.sizeBytes)}</span>;
    case 'sensitives':
      return (
        <span className="flex flex-wrap items-center gap-1.5">
          <span className="font-semibold tabular-nums text-[rgba(0,0,0,0.8)]" title={row.sensitiveCount.toLocaleString('en-US')}>
            {abbreviate(row.sensitiveCount)}
          </span>
          <LabelChips labels={row.labels} />
        </span>
      );
    case 'category':
      return row.categoryLabel ? <DotChip label={row.categoryLabel} dot={CATEGORY_DOT} /> : DASH;
    case 'documentClass':
      return row.documentClassLabel ? <DotChip label={row.documentClassLabel} dot={DOCUMENT_CLASS_DOT} /> : DASH;
    case 'tags':
      return <TagChips tags={row.tags} />;
    case 'modified':
      return row.modifiedAt === null ? DASH : <span className="whitespace-nowrap">{formatConsoleDate(row.modifiedAt)}</span>;
  }
}

/**
 * File View (FR-EP-8): every file of the agent's last scan with "Columns 9", paged and searched on the
 * server (`/endpoint/files`, Rows Per Page 10). The search is sent 300 ms after typing stops; a filter,
 * search or page-size change returns to page 1, and answers that arrive after a newer request started
 * are ignored. Rows open nothing.
 *
 * Show False Positives does not reach this view: the files route takes no such parameter.
 *
 * After File Name comes an unlabelled column with the greyed "Open lineage" icon (D3): the endpoint
 * lineage is not built. It is not one of the nine columns, so the menu neither counts nor lists it.
 */
const EndpointFileView: React.FC<Props> = ({ api, filters, search }) => {
  const [q, setQ] = useState<string | null>(null);
  // The page belongs to the filters it was chosen under: new filters read page 1.
  const [paging, setPaging] = useState({ filters, page: 1 });
  const page = paging.filters === filters ? paging.page : 1;
  const setPage = (next: number) => setPaging({ filters, page: next });
  const [pageSize, setPageSize] = useState<number>(DSPM_PAGE_SIZES[0]);
  const [result, setResult] = useState<EndpointFilePage | null>(null);
  const [error, setError] = useState<DspmError | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [hidden, toggleColumn] = useHiddenColumns<EndpointFileColumnId>();
  const skip = (page - 1) * pageSize;

  useEffect(() => {
    const next = normalizeSearch(search);
    if (next === q) return;
    const timer = window.setTimeout(() => {
      setQ(next);
      setPaging(current => ({ filters: current.filters, page: 1 }));
    }, SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [search, q]);

  useEffect(() => {
    let current = true;
    setError(null);
    api.files(filters, q, skip, pageSize).then(
      next => {
        if (!current) return;
        if (skip > 0 && skip >= next.total) {
          setPaging({ filters, page: 1 });   // past the end: the data changed since the pager was drawn
          return;
        }
        setResult(next);
      },
      err => { if (current) setError(toDspmError(err)); },
    );
    return () => { current = false; };
  }, [api, filters, q, skip, pageSize, attempt]);

  const columns = ENDPOINT_FILE_COLUMNS.filter((column, index) => index === 0 || !hidden.has(column.id));

  return (
    // Not overflow-hidden: the Columns menu opens below its button and must not be clipped on a short table.
    <div className="rounded-xl border border-[rgba(0,0,0,0.08)]">
      <header className="flex items-center border-b border-[rgba(0,0,0,0.06)] px-3 py-2">
        <ColumnsMenu columns={ENDPOINT_FILE_COLUMNS} hidden={hidden} onToggle={toggleColumn} />
      </header>
      {error && <div className="p-3"><ErrorNote error={error} onRetry={() => setAttempt(n => n + 1)} /></div>}
      {!error && !result && <LoadingNote />}
      {!error && result && (
        <>
          <div className="overflow-x-auto">
            <table className="w-full text-left">
              <thead>
                <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                  {columns.map(column => (column.id === 'fileName' ? (
                    <React.Fragment key={column.id}>
                      <th scope="col" className={`${TH} ${STICKY_NAME} ${STICKY_HEAD_BG}`}>{column.label}</th>
                      <th scope="col" aria-label="Lineage" className={`${STICKY_ICON} ${STICKY_HEAD_BG}`} />
                    </React.Fragment>
                  ) : (
                    <th key={column.id} scope="col" className={`${TH} whitespace-nowrap`}>{column.label}</th>
                  )))}
                </tr>
              </thead>
              <tbody>
                {result.rows.length === 0 && (
                  <tr><td colSpan={columns.length + 1}>{q !== null ? <NoMatchesState /> : <NoEntitiesState />}</td></tr>
                )}
                {result.rows.map((row, index) => (
                  <tr key={`${skip + index}`} className="border-b border-[rgba(0,0,0,0.04)] last:border-b-0">
                    {columns.map(column => (column.id === 'fileName' ? (
                      <React.Fragment key={column.id}>
                        <td className={`${TD} ${STICKY_NAME} bg-white`}>{renderCell(column.id, row)}</td>
                        <td className={`${TD} ${STICKY_ICON} bg-white`}>
                          <ConsoleOnlyButton variant="icon" icon="mdi:graph-outline" label="Open lineage" placement="right" />
                        </td>
                      </React.Fragment>
                    ) : (
                      <td key={column.id} className={TD}>{renderCell(column.id, row)}</td>
                    )))}
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
            maxPage={listPageLimit(pageSize)}
          />
        </>
      )}
    </div>
  );
};

export default EndpointFileView;
