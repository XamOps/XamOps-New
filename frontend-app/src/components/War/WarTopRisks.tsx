import React from 'react';
import { Icon } from '@iconify/react';
import type { WarFinding } from '../../types/war';
import { CARD, EYEBROW, severityToken, STATUS_TOKENS } from './warTokens';

interface Props {
  risks: WarFinding[];
  unverifiedCount: number;
  /** Opens this finding in the controls tab. */
  onSelectFinding?: (controlId: string) => void;
}

/**
 * The ordered fix list — confirmed exposures only.
 *
 * Ranked by risk weight: advisory severity, doubled for a hard failure, nudged by how
 * many resources are affected. Showing the weight makes the ordering auditable rather
 * than asking the reader to trust it.
 *
 * The permission and connectivity lists that used to hang off the bottom of this card now
 * live in the Scan health tab. They describe our access to the account, not the account's
 * exposure, and mixing the two put "grant us iam:ListUsers" directly under "fix these
 * first" as though it were a remediation step.
 */
const WarTopRisks: React.FC<Props> = ({ risks, unverifiedCount, onSelectFinding }) => {
  if (risks.length === 0) {
    // "Nothing to fix" and "nothing was checked" look identical if you only count
    // failures, so the unverified case gets its own wording.
    const nothingChecked = unverifiedCount > 0;

    return (
      <div className={`${CARD} p-4`}>
        <div className="flex items-center gap-3">
          <div
            className={`w-10 h-10 rounded-xl flex items-center justify-center shrink-0 border ${
              nothingChecked
                ? 'bg-[rgba(0,0,0,0.04)] border-[#e5e7eb]'
                : 'bg-emerald-50 border-emerald-100'
            }`}
          >
            <Icon
              icon={nothingChecked ? 'mdi:shield-search' : 'mdi:shield-check'}
              width={19}
              className={nothingChecked ? 'text-[rgba(0,0,0,0.6)]' : 'text-emerald-600'}
            />
          </div>
          <div className="min-w-0">
            <h2 className="text-[13.5px] font-semibold text-[rgba(0,0,0,0.88)]">
              {nothingChecked ? 'No confirmed exposures yet' : 'No open exposures'}
            </h2>
            <p className="text-[12px] text-[rgba(0,0,0,0.6)] mt-0.5 leading-relaxed">
              {nothingChecked
                ? `${unverifiedCount} controls could not be checked, so this is not an `
                  + 'all-clear. See the Scan health tab for what blocked them, then re-scan.'
                : 'Every verified control passed. Manual-review controls still need a '
                  + 'human sign-off.'}
            </p>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className={`${CARD} p-4`}>
      <div className="flex items-center gap-3 mb-3">
        <span className={`${EYEBROW} whitespace-nowrap`}>Fix these first</span>
        <div className="h-px bg-[#e5e7eb] flex-1 min-w-[20px]" />
        <span className="text-[11px] text-[rgba(0,0,0,0.55)] whitespace-nowrap hidden sm:block">
          Ranked by severity, failure type and resources affected
        </span>
      </div>

      <ol className="divide-y divide-[#e5e7eb] -mx-4">
        {risks.map((finding, index) => {
          const status = STATUS_TOKENS[finding.status];
          const severity = severityToken(finding.severity);

          return (
            <li key={finding.controlId}>
            <button
              type="button"
              onClick={() => onSelectFinding?.(finding.controlId)}
              disabled={!onSelectFinding}
              aria-label={`Open ${finding.controlId} in the controls tab`}
              className="w-full text-left flex items-start gap-3 px-4 py-2.5 transition-colors
                hover:bg-[#fafafa] focus:outline-none focus-visible:ring-2
                focus-visible:ring-inset focus-visible:ring-[rgba(0,0,0,0.55)]
                disabled:hover:bg-transparent disabled:cursor-default"
            >
              <span className="w-5 h-5 rounded-md bg-[rgba(0,0,0,0.06)] text-[10px] font-bold text-[rgba(0,0,0,0.6)] flex items-center justify-center shrink-0 mt-0.5 tabular-nums">
                {index + 1}
              </span>

              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2 flex-wrap">
                  <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] leading-snug">
                    {finding.title}
                  </span>
                  <span className={`flex items-center gap-1 text-[10.5px] uppercase tracking-wide ${severity.text}`}>
                    <Icon icon={severity.icon} width={11} />
                    {finding.severity}
                  </span>
                  <span
                    className={`px-2 py-0.5 rounded-full text-[9px] font-semibold uppercase tracking-wider ${status.pill}`}
                  >
                    {status.label}
                  </span>
                </div>
                {finding.summary && (
                  <p className="text-[11.5px] text-[rgba(0,0,0,0.6)] mt-0.5 leading-snug">
                    {finding.summary}
                  </p>
                )}
                <div className="mt-1 flex items-center gap-2 text-[10px] text-[rgba(0,0,0,0.55)] flex-wrap">
                  <span className="font-mono">{finding.controlId}</span>
                  <span aria-hidden="true">·</span>
                  <span className="truncate">{finding.attackPathLabels.join(', ')}</span>
                  {finding.affectedCount > 0 && (
                    <>
                      <span aria-hidden="true">·</span>
                      <span className="text-red-600 font-semibold tabular-nums">
                        {finding.affectedCount} resources
                      </span>
                    </>
                  )}
                </div>
              </div>

              {/* Spelled out: "wt 24" read as a typo or a unit rather than a rank input. */}
              <span
                className="text-[10px] text-[rgba(0,0,0,0.55)] shrink-0 mt-1 tabular-nums whitespace-nowrap"
                title="Risk weight: severity, failure type and affected resource count"
              >
                weight {finding.riskWeight}
              </span>

              {onSelectFinding && (
                <Icon
                  icon="mdi:chevron-right"
                  width={15}
                  className="text-[rgba(0,0,0,0.45)] shrink-0 mt-1"
                />
              )}
            </button>
            </li>
          );
        })}
      </ol>
    </div>
  );
};

export default WarTopRisks;
