import React, { useEffect, useState } from 'react';
import { Icon } from '@iconify/react';
import type { DspmError } from '../../../../types/dspm';
import type { EndpointScan, EndpointScanPage } from '../../../../types/dspmDrawers';
import type { LazyResource } from '../../../../hooks/useDspmDatastoreDrawer';
import type { EndpointDrawerApi } from '../../../../hooks/useDspmEndpointDrawer';
import { DSPM_PAGE_SIZES } from '../../../../hooks/useDspmData';
import { toDspmError } from '../../../../hooks/useDspmDatastores';
import { abbreviate, formatConsoleDate } from '../datastoreFormat';
import { endpointScanStatusVisual } from '../datastoreVisuals';
import { ResourceView, TH, useHiddenColumns } from '../drawer/TabParts';
import { ColumnsMenu } from '../ui/ColumnsMenu';
import { EmptyState } from '../ui/EmptyState';
import { TableFooter, listPageLimit } from '../ui/TableFooter';

type ScanColumnId = 'startTime' | 'endTime' | 'status' | 'sensitiveFiles' | 'totalFiles';

/** Table order; Start Time is always shown. */
const COLUMNS: ReadonlyArray<{ id: ScanColumnId; label: string }> = [
  { id: 'startTime', label: 'Start Time' },
  { id: 'endTime', label: 'End Time' },
  { id: 'status', label: 'Scan Status' },
  { id: 'sensitiveFiles', label: 'Sensitive Files' },
  { id: 'totalFiles', label: 'Total Files' },
];

const DASH = <span className="text-[rgba(0,0,0,0.4)]">-</span>;

function renderCell(column: ScanColumnId, scan: EndpointScan): React.ReactNode {
  switch (column) {
    case 'startTime':
      return scan.startTime === null ? DASH : formatConsoleDate(scan.startTime);
    case 'endTime':
      return scan.endTime === null ? DASH : formatConsoleDate(scan.endTime);
    case 'status': {
      if (!scan.statusLabel) return DASH;
      const visual = endpointScanStatusVisual(scan.status);
      return (
        <span className="inline-flex items-center gap-1">
          <Icon icon={visual.icon} width={14} className={visual.cls} />
          {scan.statusLabel}
        </span>
      );
    }
    case 'sensitiveFiles':
      return scan.sensitiveFiles === null ? DASH : scan.sensitiveFiles.toLocaleString('en-US');
    case 'totalFiles':
      // An in-progress scan reports -1 (GAP-50); the backend already sends null for it.
      return scan.totalFiles === null || scan.totalFiles < 0 ? DASH : abbreviate(scan.totalFiles);
  }
}

/**
 * The Endpoint drawer's Scan History (FR-EP-10): the agent's runs, newest first as returned, paged on the
 * server (`/endpoint/scans`, Rows Per Page 10), with a "Columns 5" chooser. A missing end time or total
 * reads "-". Answers that arrive after a newer request started are ignored, and a page past the end
 * returns to page 1.
 */
const EndpointScanHistoryTab: React.FC<{ api: EndpointDrawerApi }> = ({ api }) => {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState<number>(DSPM_PAGE_SIZES[0]);
  const [result, setResult] = useState<EndpointScanPage | null>(null);
  const [error, setError] = useState<DspmError | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [hidden, toggleColumn] = useHiddenColumns<ScanColumnId>();
  const skip = (page - 1) * pageSize;

  useEffect(() => {
    let current = true;
    setError(null);
    api.scans(skip, pageSize).then(
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
  }, [api, skip, pageSize, attempt]);

  const scans: LazyResource<EndpointScanPage> = {
    data: result, error, loading: result === null && error === null, reload: () => setAttempt(n => n + 1),
  };
  const columns = COLUMNS.filter((column, index) => index === 0 || !hidden.has(column.id));

  return (
    <ResourceView resource={scans}>
      {data => (
        // Not overflow-hidden: the Columns menu opens below its button and must not be clipped on a short table.
        <div className="rounded-xl border border-[rgba(0,0,0,0.08)]">
          <header className="flex items-center border-b border-[rgba(0,0,0,0.06)] px-3 py-2">
            <ColumnsMenu columns={COLUMNS} hidden={hidden} onToggle={toggleColumn} />
          </header>
          <div className="overflow-x-auto">
            <table className="w-full text-left">
              <thead>
                <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                  {columns.map(column => (
                    <th key={column.id} scope="col" className={`${TH} whitespace-nowrap`}>{column.label}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.rows.length === 0 && (
                  <tr>
                    <td colSpan={columns.length}>
                      <EmptyState
                        title="No scan history yet"
                        body="Runs appear here once this device has completed a scan."
                      />
                    </td>
                  </tr>
                )}
                {data.rows.map((scan, index) => (
                  <tr key={`${skip + index}`} className="border-b border-[rgba(0,0,0,0.04)] last:border-b-0">
                    {columns.map(column => (
                      <td key={column.id} className="px-3 py-2.5 align-middle text-xs text-[rgba(0,0,0,0.75)]">
                        {renderCell(column.id, scan)}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <TableFooter
            page={page}
            pageSize={pageSize}
            total={data.total}
            onPage={setPage}
            onPageSize={size => { setPageSize(size); setPage(1); }}
            maxPage={listPageLimit(pageSize)}
          />
        </div>
      )}
    </ResourceView>
  );
};

export default EndpointScanHistoryTab;
