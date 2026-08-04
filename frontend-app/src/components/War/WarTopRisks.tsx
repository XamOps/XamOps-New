import React from 'react';
import { Icon } from '@iconify/react';
import type { WarFinding } from '../../types/war';
import { CARD, EYEBROW, severityToken, STATUS_TOKENS } from './warTokens';

interface Props {
  risks: WarFinding[];
  deniedActions: string[];
  /** Non-authorization failures. Present so this card can avoid mislabelling them. */
  blockedActions: string[];
  unverifiedCount: number;
}

/**
 * The ordered fix list — confirmed exposures only.
 *
 * Ranked by risk weight: advisory severity, doubled for a hard failure, nudged by how
 * many resources are affected. Showing the weight makes the ordering auditable rather
 * than asking the reader to trust it.
 */
const WarTopRisks: React.FC<Props> = ({
  risks, deniedActions, blockedActions, unverifiedCount,
}) => {
  const hasActionLists = deniedActions.length > 0 || blockedActions.length > 0;

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
              className={nothingChecked ? 'text-[rgba(0,0,0,0.45)]' : 'text-emerald-600'}
            />
          </div>
          <div className="min-w-0">
            <h2 className="text-[13.5px] font-semibold text-[rgba(0,0,0,0.88)]">
              {nothingChecked ? 'No confirmed exposures yet' : 'No open exposures'}
            </h2>
            <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-0.5 leading-relaxed">
              {nothingChecked
                ? `${unverifiedCount} controls could not be checked, so this is not an `
                  + 'all-clear. Resolve the scan problem above and re-run.'
                : 'Every verified control passed. Manual-review controls still need a '
                  + 'human sign-off.'}
            </p>
          </div>
        </div>

        {hasActionLists && (
          <div className="mt-4 pt-4 border-t border-[rgba(0,0,0,0.06)] space-y-4">
            <ActionLists deniedActions={deniedActions} blockedActions={blockedActions} />
          </div>
        )}
      </div>
    );
  }

  return (
    <div className={`${CARD} p-4`}>
      <div className="flex items-center gap-3 mb-3">
        <span className={`${EYEBROW} whitespace-nowrap`}>Fix these first</span>
        <div className="h-px bg-[#e5e7eb] flex-1 min-w-[20px]" />
        <span className="text-[11px] text-[rgba(0,0,0,0.35)] whitespace-nowrap hidden sm:block">
          Ranked by severity, failure type and resources affected
        </span>
      </div>

      <ol className="divide-y divide-[#e5e7eb] -mx-4">
        {risks.map((finding, index) => {
          const status = STATUS_TOKENS[finding.status];
          const severity = severityToken(finding.severity);

          return (
            <li key={finding.controlId} className="flex items-start gap-3 px-4 py-2.5">
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
                  <p className="text-[11.5px] text-[rgba(0,0,0,0.45)] mt-0.5 leading-snug">
                    {finding.summary}
                  </p>
                )}
                <div className="mt-1 flex items-center gap-2 text-[10px] text-[rgba(0,0,0,0.35)] flex-wrap">
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

              <span
                className="text-[10px] text-[rgba(0,0,0,0.3)] shrink-0 mt-1 tabular-nums"
                title="Risk weight: severity, failure type and affected resource count"
              >
                wt {finding.riskWeight}
              </span>
            </li>
          );
        })}
      </ol>

      {hasActionLists && (
        <div className="mt-4 pt-4 border-t border-[rgba(0,0,0,0.06)] space-y-4">
          <ActionLists deniedActions={deniedActions} blockedActions={blockedActions} />
        </div>
      )}
    </div>
  );
};

/**
 * Only genuine IAM denials are presented as permissions to grant. Actions that failed
 * on expired credentials or an unreachable endpoint are listed separately, because
 * granting them would not change the outcome.
 */
const ActionLists: React.FC<{
  deniedActions: string[];
  blockedActions: string[];
}> = ({ deniedActions, blockedActions }) => (
  <>
    {deniedActions.length > 0 && (
      <ActionList
        icon="mdi:key-alert-outline"
        title={`${deniedActions.length} permission${deniedActions.length === 1 ? '' : 's'} missing`}
        description="The role was denied these actions. Grant them to the XamOps role so the
          score reflects reality instead of coverage gaps."
        actions={deniedActions}
      />
    )}

    {blockedActions.length > 0 && (
      <ActionList
        icon="mdi:cloud-off-outline"
        title={`${blockedActions.length} call${blockedActions.length === 1 ? '' : 's'} could not reach AWS`}
        description="These are not permission problems — the calls failed before
          authorization. Granting them would change nothing; fix the cause reported above,
          then re-scan."
        actions={blockedActions}
      />
    )}
  </>
);

const ActionList: React.FC<{
  icon: string;
  title: string;
  description: string;
  actions: string[];
}> = ({ icon, title, description, actions }) => (
  <div>
    <div className="flex items-center gap-1.5 mb-1.5">
      <Icon icon={icon} width={13} className="text-[rgba(0,0,0,0.4)]" />
      <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.6)] uppercase tracking-widest">
        {title}
      </span>
    </div>
    <p className="text-[11.5px] text-[rgba(0,0,0,0.45)] mb-2 leading-relaxed max-w-3xl">
      {description}
    </p>
    <div className="flex flex-wrap gap-1.5">
      {actions.map((action) => (
        <code
          key={action}
          className="text-[10px] font-mono bg-[#fafafa] border border-[#e5e7eb] rounded px-1.5 py-0.5 text-[rgba(0,0,0,0.6)]"
        >
          {action}
        </code>
      ))}
    </div>
  </div>
);

export default WarTopRisks;
