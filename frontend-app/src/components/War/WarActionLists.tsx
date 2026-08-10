import React from 'react';
import { Icon } from '@iconify/react';

/** Human name for a provider, for copy that addresses the customer's own console. */
export const providerLabel = (provider?: string | null): string => {
  switch (provider) {
    case 'GCP':
      return 'Google Cloud';
    case 'Azure':
      return 'Azure';
    default:
      return 'AWS';
  }
};

interface Props {
  deniedActions: string[];
  /** Non-authorization failures. Present so this never mislabels them as permissions. */
  blockedActions: string[];
  provider?: string | null;
}

/**
 * The two API-failure lists, kept apart on purpose.
 *
 * Only genuine IAM denials are presented as permissions to grant. Actions that failed on
 * expired credentials or an unreachable endpoint are listed separately, because granting
 * them would not change the outcome — and sending someone to rewrite a policy when the
 * real problem is upstream costs them an afternoon.
 */
const WarActionLists: React.FC<Props> = ({ deniedActions, blockedActions, provider }) => (
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
        title={`${blockedActions.length} call${blockedActions.length === 1 ? '' : 's'} could not reach ${providerLabel(provider)}`}
        description={`These are not permission problems — the calls failed before
          authorization. Granting them would change nothing; fix the cause reported above,
          then re-scan.`}
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
      <Icon icon={icon} width={13} className="text-[rgba(0,0,0,0.6)]" />
      <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.6)] uppercase tracking-widest">
        {title}
      </span>
    </div>
    <p className="text-[11.5px] text-[rgba(0,0,0,0.6)] mb-2 leading-relaxed max-w-3xl">
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

export default WarActionLists;
