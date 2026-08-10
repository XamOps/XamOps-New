import React from 'react';
import { Icon } from '@iconify/react';
import type { WarReport } from '../../types/war';
import WarActionLists, { providerLabel } from './WarActionLists';
import { CARD, EYEBROW } from './warTokens';

interface Props {
  report: WarReport;
  onRescan: () => void;
  isRefreshing: boolean;
}

/**
 * How much of the account the scan could actually see.
 *
 * This exists because coverage was previously scattered: a "x of y verified" chip in the
 * score card's metadata strip, the region list crushed into the same row, the permission
 * lists buried under the fix list, and per-region failures behind a collapsed disclosure
 * almost nobody opened. All four answer one question — how much of this account did we
 * fail to read, and what do I grant to fix that — so they belong together with room to
 * be read.
 *
 * Distinct from the findings tab: nothing here is a statement about the customer's
 * security posture. It is a statement about ours.
 */
const WarScanHealth: React.FC<Props> = ({ report, onRescan, isRefreshing }) => {
  const unverified = report.statusCounts.UNKNOWN ?? 0;
  const manual = report.statusCounts.MANUAL ?? 0;
  const notApplicable = report.statusCounts.NOT_APPLICABLE ?? 0;
  const scoreable = report.verifiedControls + unverified;
  const coverage = scoreable > 0
    ? Math.round((report.verifiedControls / scoreable) * 100)
    : 0;

  const hasActionLists = report.deniedActions.length > 0 || report.blockedActions.length > 0;
  const isClean = unverified === 0
    && report.scanWarnings.length === 0
    && !hasActionLists;

  return (
    <div className="space-y-3">
      {/* Coverage */}
      <div className={`${CARD} p-4`}>
        <div className="flex items-center gap-3 mb-3">
          <span className={`${EYEBROW} whitespace-nowrap`}>Coverage</span>
          <div className="h-px bg-[#e5e7eb] flex-1 min-w-[20px]" />
          <span className="text-[11px] text-[rgba(0,0,0,0.55)] whitespace-nowrap hidden sm:block">
            How much of the baseline produced a real verdict
          </span>
        </div>

        <div className="flex items-baseline gap-2">
          <span className="text-[28px] font-bold text-black leading-none tabular-nums">
            {coverage}
            <span className="text-[13px] font-medium text-[rgba(0,0,0,0.55)]">%</span>
          </span>
          <span className="text-[12px] text-[rgba(0,0,0,0.6)]">
            {report.verifiedControls} of {scoreable} checkable controls verified
          </span>
        </div>

        {/* Stacked bar: verified vs blocked, over the checkable set only. */}
        <div
          className="mt-3 h-2 w-full bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden flex"
          role="img"
          aria-label={`${report.verifiedControls} verified, ${unverified} could not be checked`}
        >
          <div
            className={coverage >= 50 ? 'h-full bg-emerald-600' : 'h-full bg-amber-600'}
            style={{ width: `${coverage}%` }}
          />
          <div className="h-full bg-[rgba(0,0,0,0.25)]" style={{ width: `${100 - coverage}%` }} />
        </div>

        <div className="mt-3 grid grid-cols-2 sm:grid-cols-4 gap-2.5">
          <Fact label="Verified" value={report.verifiedControls} hint="Produced a verdict" />
          <Fact label="Unverified" value={unverified} hint="Scan was blocked" />
          <Fact label="Manual" value={manual} hint="No API can prove" />
          <Fact label="Not in use" value={notApplicable} hint="Service absent" />
        </div>

        {!report.assessable && (
          <p className="mt-3 text-[12px] text-[rgba(0,0,0,0.6)] leading-relaxed">
            Below the 50% threshold this review needs before it will publish a score. The
            findings that did complete are real, but they are too small a sample to rate the
            account.
          </p>
        )}
      </div>

      {/* Regions */}
      {report.scannedRegions.length > 0 && (
        <div className={`${CARD} p-4`}>
          <div className="flex items-center gap-3 mb-3">
            <span className={`${EYEBROW} whitespace-nowrap`}>
              {report.provider === 'AWS' ? 'Regions scanned' : 'Scope'}
            </span>
            <div className="h-px bg-[#e5e7eb] flex-1 min-w-[20px]" />
            <span className="text-[11px] text-[rgba(0,0,0,0.55)] tabular-nums">
              {report.scannedRegions.length}
            </span>
          </div>
          <div className="flex flex-wrap gap-1.5">
            {report.scannedRegions.map((region) => (
              <code
                key={region}
                className="text-[10.5px] font-mono bg-[#fafafa] border border-[#e5e7eb] rounded px-1.5 py-0.5 text-[rgba(0,0,0,0.6)]"
              >
                {region}
              </code>
            ))}
          </div>
          {report.provider === 'AWS' && (
            <p className="mt-2.5 text-[11.5px] text-[rgba(0,0,0,0.6)] leading-relaxed">
              Most AWS resources are regional, so anything outside this list was not
              examined. A region missing here usually means it has no active resources.
            </p>
          )}
        </div>
      )}

      {/* Permissions */}
      {hasActionLists && (
        <div className={`${CARD} p-4 space-y-4`}>
          <div className="flex items-center gap-3">
            <span className={`${EYEBROW} whitespace-nowrap`}>API access</span>
            <div className="h-px bg-[#e5e7eb] flex-1 min-w-[20px]" />
          </div>
          <WarActionLists
            deniedActions={report.deniedActions}
            blockedActions={report.blockedActions}
            provider={report.provider}
          />
        </div>
      )}

      {/* Per-region / per-service failures, expanded — this tab has the room. */}
      {report.scanWarnings.length > 0 && (
        <div className={`${CARD} p-4`}>
          <div className="flex items-center gap-3 mb-3">
            <span className={`${EYEBROW} whitespace-nowrap`}>Incomplete checks</span>
            <div className="h-px bg-[#e5e7eb] flex-1 min-w-[20px]" />
            <span className="text-[11px] text-[rgba(0,0,0,0.55)] tabular-nums">
              {report.scanWarnings.length}
            </span>
          </div>
          <ul className="space-y-1 max-h-[28rem] overflow-y-auto">
            {report.scanWarnings.map((warning, i) => (
              <li
                key={i}
                className="text-[11px] font-mono text-[rgba(0,0,0,0.6)] bg-[#fafafa] border border-[#e5e7eb] rounded px-2 py-1.5 leading-relaxed break-words"
              >
                {warning}
              </li>
            ))}
          </ul>
        </div>
      )}

      {isClean ? (
        <div className={`${CARD} p-4 flex items-start gap-3`}>
          <div className="w-9 h-9 rounded-xl bg-emerald-50 border border-emerald-100 flex items-center justify-center shrink-0">
            <Icon icon="mdi:check-circle-outline" width={18} className="text-emerald-700" />
          </div>
          <div className="min-w-0">
            <h3 className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">
              Full read access
            </h3>
            <p className="text-[12px] text-[rgba(0,0,0,0.6)] mt-0.5 leading-relaxed">
              Every automated control returned a verdict and no API call was refused, so the
              score reflects your whole {providerLabel(report.provider)} footprint rather
              than the part we could see.
            </p>
          </div>
        </div>
      ) : (
        <div className="flex justify-end">
          <button
            onClick={onRescan}
            disabled={isRefreshing}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-[12px] font-medium hover:bg-black transition-colors disabled:opacity-50"
          >
            <Icon
              icon={isRefreshing ? 'mdi:loading' : 'mdi:refresh'}
              width={14}
              className={isRefreshing ? 'animate-spin' : ''}
            />
            {isRefreshing ? 'Scanning…' : 'Re-scan after granting access'}
          </button>
        </div>
      )}
    </div>
  );
};

const Fact: React.FC<{ label: string; value: number; hint: string }> = ({
  label, value, hint,
}) => (
  <div className="border border-[#e5e7eb] rounded-lg p-2.5">
    <div className="text-[10px] font-semibold text-[rgba(0,0,0,0.55)] uppercase tracking-widest truncate">
      {label}
    </div>
    <div className="mt-1 text-[20px] font-bold text-black leading-none tabular-nums">{value}</div>
    <div className="text-[10px] text-[rgba(0,0,0,0.55)] leading-tight mt-1 truncate">{hint}</div>
  </div>
);

export default WarScanHealth;
