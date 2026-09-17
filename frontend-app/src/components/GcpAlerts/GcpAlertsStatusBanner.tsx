import React from 'react';
import { Icon } from '@iconify/react';
import { formatAbsoluteTime, formatRelativeTime } from './resourceDisplay';

interface Props {
  degraded: boolean;
  degradedReasons: string[];
  generatedAt: string | null;
  fromCache: boolean;
  totalFindings: number;
  rowCount: number;
  onRetry: () => void;
  isRefreshing: boolean;
}

/**
 * States the freshness and completeness of the data, above the table.
 *
 * Two things this replaces. The topbar carried a pulsing green "Active monitoring" dot that was
 * hardcoded markup — it made the same claim whether the data was a second or a week old. And a
 * failed scan returned an empty list, which the table rendered as a green "All Clear!". Telling
 * someone their cloud is clean because we could not read it is the one mistake a security page
 * cannot make, so a degraded result now says so explicitly and offers a retry.
 */
const GcpAlertsStatusBanner: React.FC<Props> = ({
  degraded,
  degradedReasons,
  generatedAt,
  fromCache,
  totalFindings,
  rowCount,
  onRetry,
  isRefreshing,
}) => {
  if (degraded) {
    return (
      <div
        role="alert"
        className="bg-amber-50 border border-amber-200 rounded-xl px-5 py-4 flex items-start gap-3"
      >
        <Icon icon="mdi:alert-outline" width={20} className="text-amber-600 flex-shrink-0 mt-0.5" />
        <div className="flex-1 min-w-0">
          <p className="text-[13px] font-semibold text-amber-900">
            This list may be incomplete
          </p>
          <p className="text-[12px] text-amber-800 mt-1">
            One or more checks could not be completed, so treat an absence of alerts here as unknown
            rather than clear.
          </p>
          {degradedReasons.length > 0 && (
            <ul className="mt-2 space-y-1">
              {degradedReasons.map((reason, i) => (
                <li key={i} className="text-[12px] text-amber-800 flex items-start gap-1.5">
                  <span aria-hidden="true" className="mt-[7px] w-1 h-1 rounded-full bg-amber-500 flex-shrink-0" />
                  <span className="min-w-0">{reason}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
        <button
          onClick={onRetry}
          disabled={isRefreshing}
          className="flex-shrink-0 flex items-center gap-1.5 px-3 py-1.5 bg-amber-600 hover:bg-amber-700 text-white text-[12px] font-semibold rounded-lg transition-all disabled:opacity-50"
        >
          <Icon icon="mdi:refresh" width={13} className={isRefreshing ? 'animate-spin' : ''} />
          Retry
        </button>
      </div>
    );
  }

  const grouped = totalFindings > rowCount;

  return (
    <div className="flex items-center flex-wrap gap-x-4 gap-y-1.5 px-1">
      <span className="flex items-center gap-1.5 text-[12px] text-[rgba(0,0,0,0.5)]">
        <Icon icon="mdi:clock-outline" width={13} className="text-[rgba(0,0,0,0.35)]" />
        Data as of{' '}
        <span
          className="font-medium text-[rgba(0,0,0,0.7)]"
          title={formatAbsoluteTime(generatedAt)}
        >
          {formatRelativeTime(generatedAt)}
        </span>
        {fromCache && (
          <span className="text-[rgba(0,0,0,0.35)]">(cached)</span>
        )}
      </span>

      {grouped && (
        <span className="flex items-center gap-1.5 text-[12px] text-[rgba(0,0,0,0.5)]">
          <Icon icon="mdi:layers-outline" width={13} className="text-[rgba(0,0,0,0.35)]" />
          <span className="font-medium text-[rgba(0,0,0,0.7)]">
            {totalFindings.toLocaleString()} findings
          </span>
          grouped into {rowCount.toLocaleString()} {rowCount === 1 ? 'issue' : 'issues'}
        </span>
      )}
    </div>
  );
};

export default GcpAlertsStatusBanner;
