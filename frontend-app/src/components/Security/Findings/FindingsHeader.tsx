import React from 'react';
import { Icon } from '@iconify/react';

interface Props {
  /** A forced scan is running. */
  scanning: boolean;
  onRunScan: () => void;
}

/**
 * Page title, the auto-scan note and Run Scan, laid out as on the XamLens Misconfigurations page.
 *
 * Unlike there, Run Scan works: it re-runs the account's checks (`forceRefresh=true`). The note states
 * the scheduled refresh, `ProactiveCacheRefreshService.refreshSecurityAndPerformance` (Wednesday and
 * Sunday, 03:00).
 */
const FindingsHeader: React.FC<Props> = ({ scanning, onRunScan }) => (
  <div className="flex flex-wrap items-start justify-between gap-3">
    <h1 className="text-lg font-bold text-[rgba(0,0,0,0.88)]">Misconfigurations</h1>
    <div className="flex items-center gap-3">
      <span className="text-[11px] text-[rgba(0,0,0,0.5)]">Auto-Scan runs twice a week</span>
      <button
        type="button"
        onClick={onRunScan}
        disabled={scanning}
        className="inline-flex items-center gap-1.5 rounded-lg bg-[rgba(0,0,0,0.88)] px-3 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-black focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)] focus-visible:ring-offset-1 disabled:cursor-not-allowed disabled:opacity-50"
      >
        {scanning && <Icon icon="mdi:loading" width={14} className="animate-spin" />}
        {scanning ? 'Scanning…' : 'Run Scan'}
      </button>
    </div>
  </div>
);

export default FindingsHeader;
