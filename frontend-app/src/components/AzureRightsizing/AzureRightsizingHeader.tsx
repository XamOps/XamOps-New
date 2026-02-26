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
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-1.5 bg-blue-50 border border-blue-200 text-blue-700 text-xs font-semibold px-3 py-1.5 rounded-full">
          <i className="fas fa-magic text-xs"></i>
          <span>Powered by Azure Advisor</span>
        </div>
        {selectedAccountId && (
          <button
            onClick={onRefresh}
            disabled={isRefreshing}
            className="flex items-center gap-2 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-xs font-bold rounded-xl transition-all disabled:opacity-50 active:scale-95"
          >
            <i className={`fas fa-sync-alt text-xs ${isRefreshing ? 'animate-spin' : ''}`}></i>
            <span>{isRefreshing ? 'Analyzing...' : 'Refresh'}</span>
          </button>
        )}
      </div>
    );
    return () => clearActions();
  }, [selectedAccountId, isRefreshing, onRefresh, setActions, clearActions]);

  return null;
};

export default AzureRightsizingHeader;
