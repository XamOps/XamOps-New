import React, { useEffect, useState } from 'react';
import type { DspmError } from '../../../../types/dspm';
import type { EndpointFilePage } from '../../../../types/dspmDrawers';
import type { EndpointDrawerApi } from '../../../../hooks/useDspmEndpointDrawer';
import { DSPM_PAGE_SIZES } from '../../../../hooks/useDspmData';
import { toDspmError } from '../../../../hooks/useDspmDatastores';
import { ErrorNote, LoadingNote, TH } from '../drawer/TabParts';
import { TableFooter, listPageLimit } from '../ui/TableFooter';
import { EMPTY_FILTER_PARAMS } from './endpointFormat';
import { FileNameCell, PathCell, TD, TagChips } from './EndpointParts';

interface Props {
  api: EndpointDrawerApi;
  /** The class as returned ("system documentation"); the caller checked it with `endpointFilterValueAllowed`. */
  documentClass: string;
}

/**
 * The files of an expanded document class (FR-EP-7): "File Name | Matters Tags | File Path", from
 * `/endpoint/files` with that class as the only filter, paged on the server (Rows Per Page 10). No
 * samples. Late answers are ignored and a page past the end returns to page 1, as in the entity
 * expansion.
 */
const EndpointClassFiles: React.FC<Props> = ({ api, documentClass }) => {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState<number>(DSPM_PAGE_SIZES[0]);
  const [result, setResult] = useState<EndpointFilePage | null>(null);
  const [error, setError] = useState<DspmError | null>(null);
  const [attempt, setAttempt] = useState(0);
  const skip = (page - 1) * pageSize;

  useEffect(() => {
    let current = true;
    setError(null);
    api.files({ ...EMPTY_FILTER_PARAMS, documentClass: [documentClass] }, null, skip, pageSize).then(
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
  }, [api, documentClass, skip, pageSize, attempt]);

  if (error) return <ErrorNote error={error} onRetry={() => setAttempt(n => n + 1)} />;
  if (!result) return <LoadingNote />;

  return (
    <div className="overflow-hidden rounded-lg border border-[rgba(0,0,0,0.06)] bg-white">
      <div className="overflow-x-auto">
        <table className="w-full text-left">
          <thead>
            <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
              <th scope="col" className={TH}>File Name</th>
              <th scope="col" className={TH}>Matters Tags</th>
              <th scope="col" className={TH}>File Path</th>
            </tr>
          </thead>
          <tbody>
            {result.rows.length === 0 && (
              <tr>
                <td colSpan={3} className="px-3 py-6 text-center text-xs text-[rgba(0,0,0,0.4)]">No files found.</td>
              </tr>
            )}
            {result.rows.map((row, index) => (
              <tr key={`${skip + index}`} className="border-b border-[rgba(0,0,0,0.04)] last:border-b-0">
                <td className={TD}><FileNameCell name={row.name} extension={row.extension} /></td>
                <td className={TD}><TagChips tags={row.tags} /></td>
                <td className={TD}><PathCell path={row.path} /></td>
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
    </div>
  );
};

export default EndpointClassFiles;
