import React, { useEffect } from 'react';
import { useTopbar } from '../../contexts/TopbarContext';

interface AzureAlertsHeaderProps {
  selectedAccountId: string | null;
  isRefreshing: boolean;
  onRefresh: () => void;
}

const AzureAlertsHeader: React.FC<AzureAlertsHeaderProps> = ({
  selectedAccountId,
  isRefreshing,
  onRefresh,
}) => {
  const { setActions, clearActions } = useTopbar();

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-1.5 bg-orange-50 border border-orange-200 text-orange-700 text-xs font-semibold px-3 py-1.5 rounded-full">
          <i className="fas fa-chart-line text-xs"></i>
          <span>Powered by Azure Monitor</span>
        </div>
        {selectedAccountId && (
          <button
            onClick={onRefresh}
            disabled={isRefreshing}
            className="flex items-center gap-2 px-4 py-2 bg-orange-600 hover:bg-orange-700 text-white text-xs font-bold rounded-xl transition-all disabled:opacity-50 active:scale-95"
          >
            <i className={`fas fa-sync-alt text-xs ${isRefreshing ? 'animate-spin' : ''}`}></i>
            <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
          </button>
        )}
      </div>
    );
    return () => clearActions();
  }, [selectedAccountId, isRefreshing, onRefresh, setActions, clearActions]);

  return null;
};

export default AzureAlertsHeader;
