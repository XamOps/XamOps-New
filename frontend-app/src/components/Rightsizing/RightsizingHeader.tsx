import React, { useEffect } from 'react';
import { useTopbar } from '../../contexts/TopbarContext';

interface RightsizingHeaderProps {
  isRefreshing: boolean;
  onRefresh: () => void;
}

const RightsizingHeader: React.FC<RightsizingHeaderProps> = ({ isRefreshing, onRefresh }) => {
  const { setActions, clearActions } = useTopbar();

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        <button
          onClick={onRefresh}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50 active:scale-95"
        >
          <i className={`fas fa-sync-alt ${isRefreshing ? 'animate-spin' : ''}`}></i>
          <span>{isRefreshing ? 'Analyzing...' : 'Refresh Analysis'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isRefreshing, onRefresh]);

  return null;
};

export default RightsizingHeader;
