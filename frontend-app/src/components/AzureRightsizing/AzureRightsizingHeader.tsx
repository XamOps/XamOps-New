import React, { useEffect } from 'react';
import { useTopbar } from '../../contexts/TopbarContext';

interface AzureRightsizingHeaderProps {
  selectedAccountId: string | null;
  isRefreshing: boolean;
  onRefresh: () => void;
}

const AzureRightsizingHeader: React.FC<AzureRightsizingHeaderProps> = ({
  selectedAccountId,
  isRefreshing,
  onRefresh,
}) => {
  const { setActions, clearActions } = useTopbar();

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-2">
        <span className="w-1.5 h-1.5 rounded-full bg-blue-500" />
        <span className="text-[11px] font-medium uppercase tracking-wide text-[rgba(0,0,0,0.4)]">Azure Advisor</span>
        <span className="text-[rgba(0,0,0,0.15)] text-[12px] select-none">|</span>
        {selectedAccountId && (
          <button
            onClick={onRefresh}
            disabled={isRefreshing}
            className="flex items-center gap-1.5 text-[11px] font-semibold text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors disabled:opacity-40"
          >
            <svg
              className={`w-3 h-3 ${isRefreshing ? 'animate-spin' : ''}`}
              fill="none" stroke="currentColor" viewBox="0 0 24 24"
            >
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5}
                d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
            {isRefreshing ? 'Analyzing…' : 'Refresh'}
          </button>
        )}
      </div>
    );
    return () => clearActions();
  }, [selectedAccountId, isRefreshing, onRefresh, setActions, clearActions]);

  return null;
};

export default AzureRightsizingHeader;
