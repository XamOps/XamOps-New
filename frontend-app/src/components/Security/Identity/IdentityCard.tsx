/**
 * IdentityCard
 *
 * One IAM user or role as a card that expands in place to show what it can actually do.
 *
 * <p>Collapsed, it answers "should I look at this one" — name, risk badge, MFA state, and a
 * red flag when the identity holds more permission than it should. Expanded, it answers
 * "why" — each risk with its evidence, then the policies that produced them.
 *
 * <p>Expansion uses the {@code grid-template-rows: 1fr / 0fr} technique already used by
 * SecurityFindingsSection rather than animating height to a measured pixel value. It
 * transitions correctly when content reflows (a long policy list wrapping at a narrow
 * breakpoint) without a ResizeObserver, and it leaves the collapsed content unrendered for
 * layout purposes so a grid of 200 cards does not pay for 200 expanded bodies.
 */

import React from 'react';
import { Icon } from '@iconify/react';
import type { IamIdentity } from '../../../types/userManagement';
import { identityName, isIamUser } from '../../../types/userManagement';
import { riskToken, cardShell } from './riskTokens';
// Shared with the access graph's inspector, so a grant cannot describe itself one way here
// and another way there. See the note in AccessRows.
import { PolicyRow, RiskRow } from './AccessRows';

interface IdentityCardProps {
  identity: IamIdentity;
  isExpanded: boolean;
  onToggle: () => void;
  /** Hidden for GCP/Azure, where the backend returns no risk assessment. */
  showRisk: boolean;
  /** AWS-only; GCP service accounts and Azure principals have no MFA signal here. */
  showMfa: boolean;
  /**
   * What this provider calls an attached grant, singular and plural.
   *
   * <p>AWS attaches policies, GCP grants roles, Azure assigns a role definition. Calling all
   * three "policies" would be wrong on two providers and would not match the words the customer
   * sees in their own cloud console.
   */
  accessNoun: { one: string; many: string };
  formatDate: (date: string | null) => string;
  isOldDate: (date: string | null) => boolean;
  onCopy: (text: string) => void;
}

const IdentityCard: React.FC<IdentityCardProps> = ({
  identity,
  isExpanded,
  onToggle,
  showRisk,
  showMfa,
  accessNoun,
  formatDate,
  isOldDate,
  onCopy,
}) => {
  const user = isIamUser(identity) ? identity : null;
  const role = isIamUser(identity) ? null : identity;
  const name = identityName(identity);

  const flagged = showRisk && Boolean(identity.excessPermissions);
  const token = riskToken(showRisk ? identity.riskLevel : undefined);
  const risks = identity.risks ?? [];
  const policies = identity.attachedPolicies ?? [];

  const panelId = `identity-panel-${user ? user.userId : role!.roleId}`;

  return (
    <div className={`rounded-xl border shadow-sm transition-colors ${cardShell(flagged)}`}>
      {/* Header — the whole strip is the toggle, so the click target matches the affordance. */}
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={isExpanded}
        aria-controls={panelId}
        className="w-full text-left px-4 py-3.5 flex items-start gap-3 rounded-xl focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.4)] focus-visible:ring-inset"
      >
        <div className={`w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0 border ${
          flagged
            ? 'bg-red-100 border-red-200'
            : user
              ? 'bg-indigo-50 border-indigo-100'
              : 'bg-violet-50 border-violet-100'
        }`}>
          {user ? (
            <span className={`text-xs font-bold ${flagged ? 'text-red-600' : 'text-indigo-600'}`}>
              {name.substring(0, 2).toUpperCase()}
            </span>
          ) : (
            <Icon icon="mdi:robot-outline" width={16} className={flagged ? 'text-red-600' : 'text-violet-600'} />
          )}
        </div>

        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 flex-wrap">
            <p className="text-sm font-bold text-[rgba(0,0,0,0.88)] leading-tight break-all">{name}</p>
            {flagged && (
              <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded bg-red-500 text-white text-[9px] font-bold uppercase tracking-wider">
                <Icon icon="mdi:flag" width={9} />
                Excess access
              </span>
            )}
          </div>

          <div className="flex items-center gap-2 mt-1 flex-wrap">
            {showRisk && (
              <span className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full border text-[9px] font-bold uppercase tracking-wider ${token.soft}`}>
                <span className={`w-1.5 h-1.5 rounded-full ${token.dot}`} />
                {token.label}
              </span>
            )}
            {showMfa && user && (
              <span className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[9px] font-bold uppercase tracking-wider border ${
                user.mfaEnabled
                  ? 'bg-emerald-50 border-emerald-100 text-emerald-600'
                  : 'bg-rose-50 border-rose-100 text-rose-600'
              }`}>
                <Icon icon={user.mfaEnabled ? 'mdi:shield-check' : 'mdi:shield-off-outline'} width={9} />
                {user.mfaEnabled ? 'MFA' : 'No MFA'}
              </span>
            )}
            <span className="text-[10px] text-[rgba(0,0,0,0.4)]">
              {policies.length} {policies.length === 1 ? accessNoun.one : accessNoun.many}
            </span>
            {showRisk && risks.length > 0 && (
              <span className="text-[10px] font-semibold text-[rgba(0,0,0,0.5)]">
                {risks.length} {risks.length === 1 ? 'finding' : 'findings'}
              </span>
            )}
          </div>
        </div>

        <Icon
          icon="mdi:chevron-down"
          width={18}
          className={`flex-shrink-0 mt-1 text-[rgba(0,0,0,0.35)] transition-transform duration-300 ${isExpanded ? 'rotate-180' : ''}`}
        />
      </button>

      {/* Body. grid-template-rows animates cleanly without measuring content height. */}
      <div
        id={panelId}
        style={{
          display: 'grid',
          gridTemplateRows: isExpanded ? '1fr' : '0fr',
          transition: 'grid-template-rows 300ms cubic-bezier(0.4, 0, 0.2, 1)',
        }}
      >
        <div style={{ overflow: 'hidden', minHeight: 0 }}>
          <div
            style={{
              opacity: isExpanded ? 1 : 0,
              transition: 'opacity 240ms ease',
            }}
            className="px-4 pb-4 pt-1 border-t border-[rgba(0,0,0,0.06)] space-y-4"
          >
            {/* Identifier. Kept out of the collapsed header because an ARN is long, wraps
                badly in a grid cell, and is only wanted once you have chosen this card. */}
            <div className="pt-3">
              <p className="text-[9px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.35)] mb-1">
                {user ? 'User ARN' : 'Role ARN'}
              </p>
              <button
                type="button"
                onClick={(e) => { e.stopPropagation(); onCopy(identity.arn); }}
                title="Copy ARN"
                className="group inline-flex items-start gap-1.5 text-left"
              >
                <code className="text-[10px] font-mono text-[rgba(0,0,0,0.6)] break-all group-hover:text-[rgba(0,0,0,0.88)]">
                  {identity.arn}
                </code>
                <Icon icon="mdi:content-copy" width={11} className="flex-shrink-0 mt-0.5 text-[rgba(0,0,0,0.3)] group-hover:text-[rgba(0,0,0,0.6)]" />
              </button>
            </div>

            {role?.description && (
              <div>
                <p className="text-[9px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.35)] mb-1">Description</p>
                <p className="text-xs text-[rgba(0,0,0,0.65)]">{role.description}</p>
              </div>
            )}

            {/* Risks first. The reason someone opened a red card is at the top of the panel. */}
            {showRisk && risks.length > 0 && (
              <div>
                <p className="text-[9px] font-bold uppercase tracking-wider text-red-600 mb-2.5 flex items-center gap-1.5">
                  <Icon icon="mdi:alert-octagon-outline" width={11} />
                  Excess permissions detected
                </p>
                <div className="space-y-3">
                  {risks.map((risk) => <RiskRow key={risk.code} risk={risk} />)}
                </div>
              </div>
            )}

            {showRisk && risks.length === 0 && identity.riskLevel === 'NONE' && (
              <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-emerald-50 border border-emerald-100">
                <Icon icon="mdi:shield-check" width={14} className="text-emerald-600 flex-shrink-0" />
                <p className="text-xs text-emerald-700">
                  No excess permissions found. Every attached policy was read and scoped.
                </p>
              </div>
            )}

            <div>
              <p className="text-[9px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.35)] mb-1">
                Access ({policies.length})
              </p>
              {policies.length > 0 ? (
                <div>
                  {policies.map((policy, index) => (
                    <PolicyRow
                      key={`${policy.policyName}-${policy.policyArn ?? policy.type ?? 'inline'}-${index}`}
                      policy={policy}
                    />
                  ))}
                </div>
              ) : (
                <p className="text-xs text-[rgba(0,0,0,0.35)] italic py-2">No {accessNoun.many} attached</p>
              )}
            </div>

            {user && (
              <>
                <div>
                  <p className="text-[9px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.35)] mb-1.5">Groups</p>
                  {user.groups && user.groups.length > 0 ? (
                    <div className="flex flex-wrap gap-1.5">
                      {user.groups.map((group) => (
                        <span key={group} className="inline-flex items-center gap-1 px-2 py-0.5 bg-blue-50 border border-blue-100 text-blue-700 text-[10px] font-semibold rounded-full">
                          <Icon icon="mdi:account-group-outline" width={10} />
                          {group}
                        </span>
                      ))}
                    </div>
                  ) : (
                    <p className="text-xs text-[rgba(0,0,0,0.35)] italic">No groups</p>
                  )}
                </div>

                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <p className="text-[9px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.35)] mb-1">Created</p>
                    <p className="text-xs text-[rgba(0,0,0,0.7)]">{formatDate(user.createDate)}</p>
                  </div>
                  <div>
                    <p className="text-[9px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.35)] mb-1">Last sign-in</p>
                    <p className={`text-xs font-medium ${isOldDate(user.passwordLastUsed) ? 'text-red-600' : 'text-[rgba(0,0,0,0.7)]'}`}>
                      {formatDate(user.passwordLastUsed)}
                    </p>
                  </div>
                </div>
              </>
            )}

            {role && (
              <div>
                <p className="text-[9px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.35)] mb-1">Created</p>
                <p className="text-xs text-[rgba(0,0,0,0.7)]">{formatDate(role.createDate)}</p>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

export default IdentityCard;
