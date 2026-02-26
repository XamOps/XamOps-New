import React, { useEffect } from 'react';
import { useTopbar } from '../../contexts/TopbarContext';

interface AzureFinOpsHeaderProps {
  selectedAccountId: string | null;
  isRefreshing: boolean;
  onRefresh: () => void;
}

const AzureFinOpsHeader: React.FC<AzureFinOpsHeaderProps> = ({
  selectedAccountId,
  isRefreshing,
  onRefresh,
}) => {
  const { setActions, clearActions } = useTopbar();

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        {selectedAccountId && (
          <div className="flex items-center gap-2">
            <div className="w-1.5 h-1.5 bg-green-500 rounded-full animate-pulse"></div>
            <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Live</span>
          </div>
        )}
        <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-medium bg-blue-100 text-blue-700 border border-blue-200">
          <i className="fas fa-cloud text-blue-500 text-[10px]" />
          Powered by Azure Cost Management
        </span>
        {selectedAccountId && (
          <button
            onClick={onRefresh}
            disabled={isRefreshing}
            className="flex items-center gap-2 px-4 py-2 bg-blue-600 text-white text-xs font-bold rounded-xl hover:bg-blue-700 transition-all disabled:opacity-50 active:scale-95"
          >
            <i className={`fas fa-sync-alt ${isRefreshing ? 'animate-spin' : ''}`} />
            <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
          </button>
        )}
      </div>
    );
    return () => clearActions();
  }, [isRefreshing, selectedAccountId, onRefresh, setActions, clearActions]);

  return null;
};

export default AzureFinOpsHeader;
