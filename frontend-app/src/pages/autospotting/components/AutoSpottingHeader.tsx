import React, { useEffect, useMemo } from 'react';
import { Icon } from '@iconify/react';
import { useOptionalTopbarActions } from '../../../contexts/TopbarContext';

interface AutoSpottingHeaderProps {
  /** Provider mark (`adapter.icon`). */
  icon: string;
  beta: boolean;
  accountName?: string | null;
  refreshDisabled: boolean;
  onRefresh: () => void;
}

/**
 * The page's compact header in the Topbar, like the CloudSitter and SpotAutomation headers: accent dot,
 * page name, Beta tag, the account and a Refresh action. Outside a TopbarProvider (standalone renders) it
 * renders in place, so the actions stay reachable.
 */
const AutoSpottingHeader: React.FC<AutoSpottingHeaderProps> = ({ icon, beta, accountName, refreshDisabled, onRefresh }) => {
  const topbar = useOptionalTopbarActions();
  const node = useMemo(
    () => (
      <div className="flex items-center gap-2">
        <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-indigo-500" />
        <img src={icon} alt="" className="h-3.5 w-3.5" />
        <span className="text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.55)]">AutoSpotting</span>
        {beta && (
          <span className="rounded border border-indigo-200 bg-indigo-50 px-1.5 py-px text-[10px] font-semibold uppercase tracking-wide text-indigo-700">
            Beta
          </span>
        )}
        {accountName && (
          <>
            <span aria-hidden="true" className="select-none text-[12px] text-[rgba(0,0,0,0.15)]">
              |
            </span>
            <span className="text-[11px] font-medium text-[rgba(0,0,0,0.55)]">{accountName}</span>
          </>
        )}
        <span aria-hidden="true" className="select-none text-[12px] text-[rgba(0,0,0,0.15)]">
          |
        </span>
        <button
          type="button"
          onClick={onRefresh}
          disabled={refreshDisabled}
          className="flex items-center gap-1 rounded text-[11px] font-semibold text-[rgba(0,0,0,0.6)] transition-colors hover:text-indigo-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300 disabled:cursor-not-allowed disabled:opacity-40"
        >
          <Icon icon="mdi:refresh" width={13} aria-hidden="true" />
          Refresh
        </button>
      </div>
    ),
    [icon, beta, accountName, refreshDisabled, onRefresh],
  );
  useEffect(() => {
    if (!topbar) return undefined;
    topbar.setActions(node);
    return () => topbar.clearActions();
  }, [topbar, node]);
  return topbar ? null : <div className="mb-4 flex justify-end">{node}</div>;
};

export default AutoSpottingHeader;
