import React from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreOverview, DatastoreRow } from '../../../../types/dspmDatastores';
import { DspmCloudMark, DspmProviderIcon } from '../../DspmIcons';
import { accountIcon } from '../datastoreCells';
import { consoleLinkLabel } from '../datastoreFormat';
import { ConsoleOnlyButton } from '../ui/ConsoleOnly';

interface HeaderProps {
  row: DatastoreRow;
  /** Null while the overview loads or after it failed; the header then runs on the row alone. */
  overview: DatastoreOverview | null;
  /** The close button, which takes focus when the drawer opens. */
  closeRef: React.Ref<HTMLButtonElement>;
  onClose: () => void;
}

/**
 * The drawer's title row: type icon, bold name and the raw subtype code (S3, AZURE_BLOB), the console
 * link when the overview has a usable one, the greyed Start Scan and the close X.
 */
export const DrawerHeader: React.FC<HeaderProps> = ({ row, overview, closeRef, onClose }) => {
  const consoleUrl = overview?.consoleUrl ?? null;
  const linkLabel = consoleLinkLabel(consoleUrl);
  return (
    <div className="flex items-start gap-3 px-5 pb-3 pt-4">
      <span className="mt-0.5"><DspmProviderIcon provider={row.source.provider} size={22} /></span>
      <div className="min-w-0 flex-1">
        <h2 className="truncate text-base font-bold text-[rgba(0,0,0,0.88)]" title={row.name ?? undefined}>
          {row.name ?? '-'}
        </h2>
        {row.subtype && <p className="mt-0.5 text-[11px] font-semibold text-[rgba(0,0,0,0.45)]">{row.subtype}</p>}
      </div>
      {consoleUrl && linkLabel && (
        <a
          href={consoleUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex flex-shrink-0 items-center gap-1.5 rounded-lg border border-[rgba(0,0,0,0.12)] px-2.5 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.7)] transition-colors hover:bg-[rgba(0,0,0,0.04)]"
        >
          <DspmCloudMark provider={row.source.provider} size={12} />
          {linkLabel}
          <Icon icon="mdi:open-in-new" width={12} className="text-[rgba(0,0,0,0.45)]" />
        </a>
      )}
      <ConsoleOnlyButton variant="primary">Start Scan</ConsoleOnlyButton>
      <button
        ref={closeRef}
        type="button"
        onClick={onClose}
        aria-label="Close"
        className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-md border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)] transition-colors hover:bg-[rgba(0,0,0,0.04)] hover:text-[rgba(0,0,0,0.85)]"
      >
        <Icon icon="mdi:close" width={14} />
      </button>
    </div>
  );
};

export type ChipDot = 'green' | 'blue' | 'red' | 'neutral';

const DOT_CLASS: Record<ChipDot, string> = {
  green: 'bg-emerald-500',
  blue: 'bg-sky-500',
  red: 'bg-rose-500',
  neutral: 'bg-[rgba(0,0,0,0.3)]',
};

/** The fallback dot, by the list row's scan status. */
const ROW_STATUS_DOT: Record<string, ChipDot> = {
  CLASSIFIED: 'green',
  SCANNING: 'blue',
  NOT_SCANNED: 'red',
  FAILED: 'red',
  STOPPED: 'red',
};

export interface ScanChip {
  text: string;
  /** The progress as "99%", or null when the chip shows none. */
  percent: string | null;
  dot: ChipDot;
}

/**
 * The Scan Status chip (design 6.7):
 * - overview `SCAN_STOPPED`: "Scan Stopped", blue dot, never a percentage;
 * - `SCANNING_OUTPUT_PROCESSED`: its label and the floored progress when it is a number, green dot;
 * - any other status: its label and the progress, neutral dot;
 * - no status, or no overview (loading or failed): the list row's label, with a dot by the row's status.
 *
 * A failed scan-status request only removes the percentage (A13); `progress` is then null.
 */
export function scanChip(row: DatastoreRow, overview: DatastoreOverview | null, progress: number | null): ScanChip {
  const status = overview?.scanningStatus ?? null;
  if (status === null) {
    return {
      text: row.scan.statusLabel ?? '-',
      percent: null,
      dot: ROW_STATUS_DOT[row.scan.status ?? ''] ?? 'neutral',
    };
  }
  const text = overview?.scanningStatusLabel ?? status;
  if (status === 'SCAN_STOPPED') return { text, percent: null, dot: 'blue' };
  const percent = progress !== null && Number.isFinite(progress) ? `${Math.floor(progress)}%` : null;
  return { text, percent, dot: status === 'SCANNING_OUTPUT_PROCESSED' ? 'green' : 'neutral' };
}

interface InfoCardProps {
  row: DatastoreRow;
  overview: DatastoreOverview | null;
  /** The scan-status progress, or null while it loads, after it failed or when it is not numeric. */
  progress: number | null;
  /** The sync status, drawn after the Scan Status chip (`SyncStatusBadge`). */
  syncStatus?: React.ReactNode;
  /** The Actions menu at the card's end (`DrawerActionsMenu`). */
  actions: React.ReactNode;
}

/** The card under the title: Source/Account (the list's rule), the Scan Status chip, the sync status and Actions. */
export const DrawerInfoCard: React.FC<InfoCardProps> = ({ row, overview, progress, syncStatus, actions }) => {
  const chip = scanChip(row, overview, progress);
  return (
    <div className="flex flex-wrap items-center gap-x-8 gap-y-3 rounded-xl border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.015)] px-4 py-3">
      <div className="min-w-0">
        <div className="text-[10px] text-[rgba(0,0,0,0.45)]">Source/Account</div>
        <div className="mt-1 flex min-w-0 items-center gap-1.5 text-xs font-semibold text-[rgba(0,0,0,0.8)]">
          {accountIcon(row)}
          <span className="max-w-[16rem] truncate" title={row.source.accountLabel ?? undefined}>
            {row.source.accountLabel ?? '-'}
          </span>
        </div>
      </div>
      <div>
        <div className="text-[10px] text-[rgba(0,0,0,0.45)]">Scan Status</div>
        <span
          data-scan-chip=""
          className="mt-1 inline-flex items-center gap-1.5 rounded-full border border-[rgba(0,0,0,0.1)] bg-white px-2 py-0.5 text-[11px] font-semibold text-[rgba(0,0,0,0.75)]"
        >
          <span aria-hidden="true" data-dot={chip.dot} className={`h-1.5 w-1.5 rounded-full ${DOT_CLASS[chip.dot]}`} />
          {chip.text}
          {chip.percent && <>{' '}<span className="text-sky-600">{chip.percent}</span></>}
        </span>
        {syncStatus}
      </div>
      <div className="ml-auto">{actions}</div>
    </div>
  );
};
