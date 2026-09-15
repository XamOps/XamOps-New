/**
 * AccessRows
 *
 * The two row renderers that explain what an identity can actually do: one grant
 * ({@link PolicyRow}) and one reason it is over-privileged ({@link RiskRow}).
 *
 * <p>Extracted from IdentityCard because the Identity & Access tab now has two views of the
 * same data — the card canvas and the access graph's node inspector — and both have to
 * describe a grant identically. Left inside the card these would have been copied into the
 * inspector, and the copy is where "Not read" or the {@code via group} chip quietly goes
 * missing from one of them. A grant that renders as safe in one view and dangerous in the
 * other is worse than either view alone.
 */

import React from 'react';
import { Icon } from '@iconify/react';
import type { IamAccessRisk, Policy } from '../../../types/userManagement';
import { riskToken } from './riskTokens';

/** Chip listing a policy's dangerous traits, so the row explains itself without expanding twice. */
export const PolicyRow: React.FC<{ policy: Policy }> = ({ policy }) => {
  const wildcards = policy.wildcardActions ?? [];
  const escalations = policy.escalationActions ?? [];

  return (
    <div className="flex flex-col gap-1.5 py-2 border-b border-[rgba(0,0,0,0.04)] last:border-b-0">
      <div className="flex items-start gap-2">
        <Icon
          icon={policy.adminAccess ? 'mdi:shield-alert' : 'mdi:file-document-outline'}
          width={13}
          className={`mt-0.5 flex-shrink-0 ${policy.adminAccess ? 'text-red-500' : 'text-[rgba(0,0,0,0.3)]'}`}
        />
        <span className="text-xs font-medium text-[rgba(0,0,0,0.75)] break-all">{policy.policyName}</span>
        {policy.type && (
          <span className={`flex-shrink-0 px-1.5 py-0.5 rounded text-[9px] font-bold uppercase tracking-wider border ${
            policy.type === 'Managed'
              ? 'bg-indigo-50 border-indigo-100 text-indigo-600'
              : 'bg-amber-50 border-amber-100 text-amber-600'
          }`}>
            {policy.type}
          </span>
        )}
        {/* Inherited grants cannot be detached from the user, so saying where they live is
            the difference between an actionable finding and a dead end. */}
        {policy.inheritedFrom && (
          <span
            className="flex-shrink-0 inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[9px] font-bold uppercase tracking-wider bg-blue-50 border border-blue-100 text-blue-700"
            title={`Inherited from group ${policy.inheritedFrom}. Remove it from the group to revoke it.`}
          >
            <Icon icon="mdi:account-group-outline" width={9} />
            via {policy.inheritedFrom}
          </span>
        )}
        {/* Stated explicitly: a policy we could not read must not look like a policy we cleared. */}
        {policy.documentAnalyzed === false && (
          <span
            className="flex-shrink-0 px-1.5 py-0.5 rounded text-[9px] font-bold uppercase tracking-wider bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)]"
            title="The policy document could not be read, so its permissions were not assessed."
          >
            Not read
          </span>
        )}
      </div>

      {(policy.adminAccess || wildcards.length > 0 || escalations.length > 0) && (
        <div className="flex flex-wrap gap-1 pl-[21px]">
          {policy.adminAccess && (
            <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded bg-red-50 border border-red-200 text-red-700 text-[9px] font-bold uppercase tracking-wider">
              <Icon icon="mdi:crown" width={9} />
              Full admin
            </span>
          )}
          {escalations.map((action) => (
            <code key={action} className="px-1.5 py-0.5 rounded bg-red-50 border border-red-200 text-red-700 text-[10px] font-mono">
              {action}
            </code>
          ))}
          {wildcards.map((action) => (
            <code key={action} className="px-1.5 py-0.5 rounded bg-orange-50 border border-orange-200 text-orange-700 text-[10px] font-mono">
              {action}
            </code>
          ))}
        </div>
      )}
    </div>
  );
};

export const RiskRow: React.FC<{ risk: IamAccessRisk }> = ({ risk }) => {
  const token = riskToken(risk.severity);
  const evidence = risk.evidence ?? [];

  return (
    <div className="flex items-start gap-2.5">
      <div className={`w-5 h-5 rounded-full flex items-center justify-center flex-shrink-0 mt-0.5 ${token.solid}`}>
        <Icon icon={token.icon} width={12} />
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-xs font-bold text-[rgba(0,0,0,0.88)]">{risk.title}</span>
          <span className={`px-1.5 py-0.5 rounded text-[9px] font-bold uppercase tracking-wider border ${token.soft}`}>
            {token.label}
          </span>
          <code className="text-[9px] font-mono text-[rgba(0,0,0,0.35)]">{risk.code}</code>
        </div>
        <p className="text-[11px] leading-[1.5] text-[rgba(0,0,0,0.6)] mt-0.5">{risk.description}</p>
        {risk.sourcePolicy && (
          <p className="text-[10px] text-[rgba(0,0,0,0.45)] mt-1">
            via <span className="font-semibold text-[rgba(0,0,0,0.6)]">{risk.sourcePolicy}</span>
          </p>
        )}
        {evidence.length > 0 && (
          <div className="flex flex-wrap gap-1 mt-1.5">
            {evidence.map((item) => (
              <code key={item} className="px-1.5 py-0.5 rounded bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] text-[10px] font-mono text-[rgba(0,0,0,0.65)]">
                {item}
              </code>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};
