import React from 'react';
import { useTopbar } from '../../contexts/TopbarContext';
// Hidden from the topbar: it sat beside the ordinary Refresh and read as a second,
// near-identical refresh control. Restore this import with the render below.
// import RefreshFromCurButton from '../Shared/RefreshFromCurButton';
import { setCostSourceMode } from '../../utils/apiClient';
import type { DateFilter } from '../../types/finops';

interface FinOpsHeaderProps {
  dateFilter: DateFilter;
  isRefreshing: boolean;
  onDateFilterChange: (filter: DateFilter) => void;
  onApplyDateFilter: () => void;
  onClearDateFilter: () => void;
  onRefresh: () => void;
  /**
   * Single AWS account to offer a CUR-only reload for, or null to hide the button.
   * A CUR belongs to one account, so a multi-account or non-AWS selection has nothing
   * to reload.
   */
  curAccountId?: string | null;
}

// curAccountId is still accepted by the props type so callers need no change, but it is
// left undestructured while the CUR button below is commented out.
const FinOpsHeader: React.FC<FinOpsHeaderProps> = ({ dateFilter, isRefreshing, onDateFilterChange, onApplyDateFilter, onClearDateFilter, onRefresh /*, curAccountId = null */ }) => {
  const today = new Date().toISOString().split('T')[0];
  const { setActions, clearActions } = useTopbar();

  React.useEffect(() => {
    setActions(
      <div className="flex items-center gap-2">

        {/* Date range */}
        <div className="flex items-center gap-1 border border-[rgba(0,0,0,0.08)] rounded-md px-2 py-0.5">
          <input
            type="date"
            value={dateFilter.startDate}
            onChange={(e) => onDateFilterChange({ ...dateFilter, startDate: e.target.value })}
            className="border-0 focus:ring-0 text-[12px] text-[rgba(0,0,0,0.7)] bg-transparent p-0 w-28 outline-none font-medium"
            max={dateFilter.endDate || today}
          />
          <span className="text-[rgba(0,0,0,0.15)] text-[12px] select-none">→</span>
          <input
            type="date"
            value={dateFilter.endDate}
            onChange={(e) => onDateFilterChange({ ...dateFilter, endDate: e.target.value })}
            className="border-0 focus:ring-0 text-[12px] text-[rgba(0,0,0,0.7)] bg-transparent p-0 w-28 outline-none font-medium"
            min={dateFilter.startDate}
            max={today}
          />
          <button
            onClick={onApplyDateFilter}
            disabled={!dateFilter.startDate || !dateFilter.endDate}
            className="ml-1 text-[12px] font-medium text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors disabled:opacity-30"
          >
            Apply
          </button>
          {(dateFilter.startDate || dateFilter.endDate) && (
            <>
              <span className="text-[rgba(0,0,0,0.15)] text-[12px] select-none">|</span>
              <button
                onClick={onClearDateFilter}
                className="text-[12px] font-medium text-[rgba(0,0,0,0.35)] hover:text-[rgba(0,0,0,0.7)] transition-colors"
              >
                ✕
              </button>
            </>
          )}
        </div>

        <span className="text-[rgba(0,0,0,0.15)] text-[12px] select-none">|</span>

        {/* Live status */}
        <div className="flex items-center gap-1.5">
          <span className="w-1.5 h-1.5 rounded-full bg-[#1BE68B] animate-pulse flex-shrink-0"></span>
          <span className="text-[11px] font-medium uppercase tracking-wide text-[rgba(0,0,0,0.4)]">Connected</span>
        </div>

        <span className="text-[rgba(0,0,0,0.15)] text-[12px] select-none">|</span>

        {/* Refresh */}
        <button
          onClick={() => { setCostSourceMode('CE'); onRefresh(); }}
          disabled={isRefreshing}
          className="flex items-center gap-1.5 text-[12px] font-medium text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors disabled:opacity-40"
        >
          <svg className={`w-3 h-3 ${isRefreshing ? 'animate-spin' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
          </svg>
          {isRefreshing ? 'Refreshing…' : 'Refresh'}
        </button>

        {/* Same reload as the button above, but from the Cost and Usage Report with no
            Cost Explorer fallback — so it also answers whether this account is really
            reading the report, which the ordinary Refresh cannot.

            Hidden: two adjacent refresh controls in the topbar. Re-enable together with
            the import at the top of this file.
        <RefreshFromCurButton accountId={curAccountId} onRefreshed={onRefresh} />
        */}
      </div>
    );
    return () => clearActions();
  }, [dateFilter, isRefreshing, onDateFilterChange, onApplyDateFilter, onClearDateFilter, onRefresh, setActions, clearActions, today]);

  return null;
};

export default FinOpsHeader;
