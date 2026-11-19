import React, { useEffect, useState } from 'react';
import type { DspmError } from '../../../../types/dspm';
import type { EndpointFilePage } from '../../../../types/dspmDrawers';
import type { EndpointDrawerApi, EndpointFilterParams } from '../../../../hooks/useDspmEndpointDrawer';
import { DSPM_PAGE_SIZES } from '../../../../hooks/useDspmData';
import { SEARCH_DEBOUNCE_MS, toDspmError } from '../../../../hooks/useDspmDatastores';
import { normalizeSearch } from '../datastoreFilters';
import { ErrorNote, LoadingNote, useHiddenColumns } from '../drawer/TabParts';
import { ColumnsMenu } from '../ui/ColumnsMenu';
import { TableFooter, listPageLimit } from '../ui/TableFooter';
import { ENDPOINT_FILE_COLUMNS, type EndpointFileColumnId } from './endpointFileColumns';
import EndpointFileTable from './EndpointFileTable';
import { NoEntitiesState, NoMatchesState } from './EndpointParts';

interface Props {
  api: EndpointDrawerApi;
  /** Memoised by the caller: a new object means new filters, page 1 and a new request. */
  filters: EndpointFilterParams;
  search: string;
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
          <EndpointFileTable
            rows={result.rows}
            loading={false}
            hidden={hidden}
            emptyState={q !== null ? <NoMatchesState /> : <NoEntitiesState />}
          />
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
