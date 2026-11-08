import React from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreScanHistory, DatastoreScanRun } from '../../../../types/dspmDatastores';
import { InfoTooltip } from '../../../ui/Tooltip';
import { usePaged } from '../../dspmShared';
import { formatConsoleDate } from '../datastoreFormat';
import { scanRunVisual } from '../datastoreVisuals';
import { LevelLabel } from '../ui/Chips';
import { ColumnsMenu } from '../ui/ColumnsMenu';
import { EmptyState } from '../ui/EmptyState';
import { TableFooter } from '../ui/TableFooter';
import { TH, useHiddenColumns } from './TabParts';

type ScanColumnId = 'scanNumber' | 'dateTime' | 'sensitiveRecords' | 'severity' | 'triggeredBy' | 'scanStatus';

/** Table order; Scan Number is always shown. */
const COLUMNS: ReadonlyArray<{ id: ScanColumnId; label: string }> = [
  { id: 'scanNumber', label: 'Scan Number' },
  { id: 'dateTime', label: 'Date & Time' },
  { id: 'sensitiveRecords', label: 'Sensitive Records' },
  { id: 'severity', label: 'Severity' },
  { id: 'triggeredBy', label: 'Triggered By' },
  { id: 'scanStatus', label: 'Scan Status' },
];

const SEVERITY_HINT = 'Sensitivity level Matters assigned to this scan.';

const DASH = <span className="text-[rgba(0,0,0,0.4)]">-</span>;

/** CRITICAL_SENSITIVITY → CRITICAL: the risk level whose icon and colour the Severity cell borrows. */
const levelOf = (sensitivityLevel: string | null) => sensitivityLevel?.replace(/_SENSITIVITY$/, '') ?? null;

function renderCell(column: ScanColumnId, run: DatastoreScanRun): React.ReactNode {
  switch (column) {
    case 'scanNumber':
      return run.executionNumber ?? DASH;
    case 'dateTime':
      return run.startTime === null ? DASH : formatConsoleDate(run.startTime);
    case 'sensitiveRecords':
      return run.sensitiveRecords === null ? DASH : run.sensitiveRecords.toLocaleString('en-US');
    case 'severity':
      // A run Matters rated NOT_SENSITIVE has no label.
      return run.severityLabel ? <LevelLabel level={levelOf(run.sensitivityLevel)} label={run.severityLabel} /> : DASH;
    case 'triggeredBy':
      return run.triggeredBy ?? DASH;
    case 'scanStatus': {
      if (!run.statusLabel) return DASH;
      const visual = scanRunVisual(run.status);
      return (
        <span className="inline-flex items-center gap-1">
          <Icon icon={visual.icon} width={14} className={visual.cls} />
          {run.statusLabel}
        </span>
      );
    }
  }
}

/**
 * Scan History: the datastore's runs, newest first as returned, paged in the browser (Rows Per Page 10),
 * with a "Columns 6" chooser.
 */
const ScanHistoryTab: React.FC<{ history: DatastoreScanHistory }> = ({ history }) => {
  const [hidden, toggleColumn] = useHiddenColumns<ScanColumnId>();
  const paged = usePaged(history.runs, 'runs');
  const columns = COLUMNS.filter((column, index) => index === 0 || !hidden.has(column.id));

  return (
    <div className="overflow-hidden rounded-xl border border-[rgba(0,0,0,0.08)]">
      <header className="flex items-center border-b border-[rgba(0,0,0,0.06)] px-3 py-2">
        <ColumnsMenu columns={COLUMNS} hidden={hidden} onToggle={toggleColumn} />
      </header>
      <div className="overflow-x-auto">
        <table className="w-full text-left">
          <thead>
            <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
              {columns.map(column => (
                <th key={column.id} scope="col" className={`${TH} whitespace-nowrap`}>
                  {column.id === 'severity' ? (
                    <span className="inline-flex items-center gap-1">
                      {column.label}
                      <InfoTooltip>{SEVERITY_HINT}</InfoTooltip>
                    </span>
                  ) : column.label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {history.runs.length === 0 && (
              <tr>
                <td colSpan={columns.length}>
                  <EmptyState
                    title="No scan history yet"
                    body="Runs appear here once this datastore has completed a scan."
                  />
                </td>
              </tr>
            )}
            {paged.items.map((run, index) => (
              <tr key={`${index}:${run.executionNumber ?? ''}`} className="border-b border-[rgba(0,0,0,0.04)] last:border-b-0">
                {columns.map(column => (
                  <td key={column.id} className="px-3 py-2.5 align-middle text-xs text-[rgba(0,0,0,0.75)]">
                    {renderCell(column.id, run)}
                  </td>
                ))}
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
  );
};

export default ScanHistoryTab;
