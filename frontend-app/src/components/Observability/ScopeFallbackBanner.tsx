import React from 'react';
import { Icon } from '@iconify/react';
import { useNavigate } from 'react-router-dom';
import { ObservabilityConfig } from '../../hooks/useObservabilityData';

/**
 * Warns when observability data is organisation-wide rather than scoped to the selected
 * cloud account.
 *
 * Each cloud account gets its own OpenObserve org. When that org doesn't exist yet, every
 * query silently falls back to the client-level org — previously a server-side log line
 * only. The user saw either nothing or another org's totals presented as though they
 * belonged to the account they had selected, with no way to tell the difference.
 */
const ScopeFallbackBanner: React.FC<{
  config: ObservabilityConfig | null;
  /** The account the user currently has selected, if any. */
  selectedAccountId?: string | null;
}> = ({ config, selectedAccountId }) => {
  const navigate = useNavigate();

  // Only meaningful when an account is selected — with no account selected,
  // organisation-wide data is exactly what the user asked for.
  if (!config || !selectedAccountId) return null;
  if (config.accountProvisioned !== false && config.scope !== 'client') return null;

  return (
    <div role="status" className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 flex items-start gap-3">
      <Icon icon="mdi:information-outline" width={18} className="mt-0.5 text-amber-600 flex-shrink-0" />
      <div className="min-w-0 flex-1">
        <p className="text-[13px] font-semibold text-amber-900">
          Showing organisation-wide data
        </p>
        <p className="text-[12px] text-amber-800 mt-0.5">
          {config.fallbackReason
            ?? `Account ${config.requestedAccountId ?? selectedAccountId} is not provisioned for observability yet, so figures below cover your whole organisation rather than this account.`}
        </p>
        <button
          onClick={() => navigate('/observability/setup')}
          className="mt-2 text-[12px] font-medium text-amber-700 hover:text-amber-900 inline-flex items-center gap-1"
        >
          Set up this account <Icon icon="mdi:arrow-right" width={13} />
        </button>
      </div>
    </div>
  );
};

export default ScopeFallbackBanner;
