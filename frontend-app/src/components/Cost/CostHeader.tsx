/**
 * CostHeader Component
 * Header with title, live data indicator, and refresh button
 */

import React from 'react';
import { useTopbar } from '../../contexts/TopbarContext';

interface CostHeaderProps {
  isLoading: boolean;
  onRefresh: () => void;
}

const CostHeader: React.FC<CostHeaderProps> = ({ isLoading, onRefresh }) => {
  const { setActions, clearActions } = useTopbar();

  // Register Cost actions in Topbar
  React.useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        <span className="flex items-center text-xs font-medium text-green-600 bg-green-50 px-3 py-1.5 rounded-lg border border-green-100">
          <span className="w-2 h-2 bg-green-500 rounded-full mr-2 animate-pulse"></span>
          Live data
        </span>
        <button
          onClick={onRefresh}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-sm font-semibold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all shadow-md hover:shadow-lg"
        >
          <i className={`fas fa-sync-alt ${isLoading ? 'fa-spin' : ''}`} />
          <span>Refresh</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [isLoading, onRefresh, setActions, clearActions]);

  return null;
};

export default CostHeader;
