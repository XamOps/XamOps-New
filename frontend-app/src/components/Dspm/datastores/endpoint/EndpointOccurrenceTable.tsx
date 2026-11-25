import React, { useEffect, useState } from 'react';
import type { DspmError } from '../../../../types/dspm';
import type { EndpointOccurrencePage } from '../../../../types/dspmDrawers';
import type { EndpointDrawerApi } from '../../../../hooks/useDspmEndpointDrawer';
import { DSPM_PAGE_SIZES } from '../../../../hooks/useDspmData';
import { toDspmError } from '../../../../hooks/useDspmDatastores';
import { InfoTooltip } from '../../../ui/Tooltip';
import { FalsePositiveCell } from '../drawer/OccurrenceTable';
import { SampleCell } from '../drawer/SampleValue';
import { ErrorNote, LoadingNote, TH } from '../drawer/TabParts';
import { TableFooter, listPageLimit } from '../ui/TableFooter';
import { FileNameCell, PathCell, TD, TagChips } from './EndpointParts';

/** DA10 [O3]: the console's ⓘ is recorded, not its text. */
const PATH_HINT = 'Full path of the file on the device.';

interface Props {
  api: EndpointDrawerApi;
  entityName: string;
  showFalsePositives: boolean;
}

/**
 * The files of an expanded entity (FR-EP-6): "File Name | Matters Tags | File Path ⓘ | Sample Data |
 * False Positive", paged on the server through `/endpoint/occurrences` (Rows Per Page 10), with
 * `showFalsePositives` on every request. Answers that arrive after a newer request started are ignored,
 * and a page past the end (the data changed since the pager was drawn) returns to page 1.
 *
 * Samples arrive masked by xamops-service (build 9), live only in this component's state and are
 * rendered by `SampleCell` alone. "Mark" is greyed.
 */
const EndpointOccurrenceTable: React.FC<Props> = ({ api, entityName, showFalsePositives }) => {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState<number>(DSPM_PAGE_SIZES[0]);
  const [result, setResult] = useState<EndpointOccurrencePage | null>(null);
  const [error, setError] = useState<DspmError | null>(null);
  const [attempt, setAttempt] = useState(0);
  const skip = (page - 1) * pageSize;

  useEffect(() => {
    let current = true;
    setError(null);
    api.occurrences(entityName, skip, pageSize, showFalsePositives).then(
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
  }, [api, entityName, skip, pageSize, showFalsePositives, attempt]);

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
              <th scope="col" className={TH}>
                <span className="inline-flex items-center gap-1">
                  File Path
                  <InfoTooltip>{PATH_HINT}</InfoTooltip>
                </span>
              </th>
              <th scope="col" className={TH}>Sample Data</th>
              <th scope="col" className={TH}>False Positive</th>
            </tr>
          </thead>
          <tbody>
            {result.rows.length === 0 && (
              <tr>
                <td colSpan={5} className="px-3 py-6 text-center text-xs text-[rgba(0,0,0,0.4)]">No occurrences found.</td>
              </tr>
            )}
            {result.rows.map((row, index) => (
              <tr key={`${skip + index}`} className="border-b border-[rgba(0,0,0,0.04)] last:border-b-0">
                <td className={TD}><FileNameCell name={row.fileName} /></td>
                <td className={TD}><TagChips tags={row.tags} /></td>
                <td className={TD}><PathCell path={row.path} /></td>
                <td className={TD}><SampleCell samples={row.samples} /></td>
                <td className={TD}><FalsePositiveCell marked={row.falsePositive} /></td>
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

export default EndpointOccurrenceTable;
