import React, { useEffect, useState } from 'react';
import type { DspmError } from '../../../../types/dspm';
import type { DatastoreOccurrencePage } from '../../../../types/dspmDatastores';
import type { DatastoreDrawerApi } from '../../../../hooks/useDspmDatastoreDrawer';
import { DSPM_PAGE_SIZES } from '../../../../hooks/useDspmData';
import { toDspmError } from '../../../../hooks/useDspmDatastores';
import { DspmObjectIcon } from '../../DspmIcons';
import { extensionOf, occurrenceRange } from '../datastoreFormat';
import { ConsoleOnlyButton } from '../ui/ConsoleOnly';
import { CopyButton } from '../ui/CopyButton';
import { TableFooter, listPageLimit } from '../ui/TableFooter';
import { SampleCell } from './SampleValue';
import { ErrorNote, LoadingNote, TH } from './TabParts';

interface Props {
  api: DatastoreDrawerApi;
  entityName: string;
  showFalsePositives: boolean;
  /**
   * The view toggle's predicate (`sensitives.database || overview.canDiscoverSchema`): database columns
   * when true, file columns otherwise.
   */
  database: boolean;
}

const DATABASE_COLUMNS = ['Database', 'Schema', 'Table', 'Column', 'Sample Data', 'False Positive'];
const FILE_COLUMNS = ['File Name', 'File Path', 'Sample Data', 'False Positive'];

const TD = 'px-3 py-2 align-middle text-xs text-[rgba(0,0,0,0.75)]';

const Value: React.FC<{ value: string | null }> = ({ value }) => <>{value ?? '-'}</>;

/** "Mark" as false positive is a console action; a marked row also carries a grey tag. */
export const FalsePositiveCell: React.FC<{ marked: boolean }> = ({ marked }) => (
  <span className="inline-flex items-center gap-1.5">
    {/* TODO(G6): rendering of marked rows unseen */}
    {marked && (
      <span className="rounded bg-[rgba(0,0,0,0.06)] px-1.5 py-0.5 text-[10px] font-semibold text-[rgba(0,0,0,0.5)]">
        False positive
      </span>
    )}
    <ConsoleOnlyButton variant="secondary" placement="left">Mark</ConsoleOnlyButton>
  </span>
);

/**
 * The occurrences of one entity, under its expanded Entity View row. Paged on the server through
 * `/sensitives/occurrences` (skip = (page − 1) × Rows Per Page); `showFalsePositives` goes with every
 * request.
 *
 * Samples arrive masked by xamops-service (build 9) and live only in this component's state: they go when the row collapses, the switch changes or
 * the drawer closes, and they are rendered by `SampleCell` alone (decision D1).
 */
const OccurrenceTable: React.FC<Props> = ({ api, entityName, showFalsePositives, database }) => {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState<number>(DSPM_PAGE_SIZES[0]);
  const [result, setResult] = useState<DatastoreOccurrencePage | null>(null);
  const [error, setError] = useState<DspmError | null>(null);
  const [attempt, setAttempt] = useState(0);
  const skip = (page - 1) * pageSize;

  useEffect(() => {
    let current = true;
    setError(null);
    api.occurrences(entityName, skip, pageSize, showFalsePositives).then(
      next => {
        if (!current) return;
        // The range comes from the request and the response total, never from a vendor echo.
        const range = occurrenceRange(skip, pageSize, next.total);
        if (skip > 0 && (range === null || range.from > range.total)) {
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

  const columns = database ? DATABASE_COLUMNS : FILE_COLUMNS;

  return (
    <div className="overflow-hidden rounded-lg border border-[rgba(0,0,0,0.06)] bg-white">
      <div className="overflow-x-auto">
        <table className="w-full text-left">
          <thead>
            <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
              {columns.map(column => <th key={column} scope="col" className={TH}>{column}</th>)}
            </tr>
          </thead>
          <tbody>
            {result.rows.length === 0 && (
              <tr>
                <td colSpan={columns.length} className="px-3 py-6 text-center text-xs text-[rgba(0,0,0,0.4)]">
                  No occurrences found.
                </td>
              </tr>
            )}
            {result.rows.map((row, index) => (
              <tr key={`${skip + index}`} className="border-b border-[rgba(0,0,0,0.04)] last:border-b-0">
                {database ? (
                  <>
                    <td className={TD}><Value value={row.databaseName} /></td>
                    <td className={TD}><Value value={row.schemaName} /></td>
                    <td className={TD}><Value value={row.tableName} /></td>
                    <td className={TD}><Value value={row.columnName} /></td>
                  </>
                ) : (
                  <>
                    <td className={TD}>
                      <span className="flex min-w-0 items-center gap-2">
                        <DspmObjectIcon objectType={null} extension={extensionOf(row.fileName)} />
                        <span className="max-w-[12rem] truncate font-semibold text-[rgba(0,0,0,0.85)]" title={row.fileName ?? undefined}>
                          {row.fileName ?? '-'}
                        </span>
                      </span>
                    </td>
                    <td className={TD}>
                      {row.url ? (
                        <span className="flex min-w-0 items-center gap-1">
                          <span className="max-w-[16rem] truncate" title={row.url}>{row.url}</span>
                          <CopyButton text={row.url} label="Copy path" />
                        </span>
                      ) : '-'}
                    </td>
                  </>
                )}
                <td className={TD}><SampleCell samples={row.samples} /></td>
                <td className={TD}><FalsePositiveCell marked={row.falsePositive} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {/* With skip = (page − 1) × pageSize, the footer's "Showing a - b of N" is occurrenceRange(skip, limit, total). */}
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

export default OccurrenceTable;
