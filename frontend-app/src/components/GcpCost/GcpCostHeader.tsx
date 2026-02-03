import React, { useEffect } from 'react';
import { useTopbar } from '../../contexts/TopbarContext';

interface GcpCostHeaderProps {
  isLoading: boolean;
  onRefresh: () => void;
}

const GcpCostHeader: React.FC<GcpCostHeaderProps> = ({ isLoading, onRefresh }) => {
  const { setActions, clearActions } = useTopbar();

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        {/* Status & Refresh */}
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2">
            <div className="w-1.5 h-1.5 bg-green-500 rounded-full animate-pulse shadow-[0_0_8px_rgba(34,197,94,0.4)]"></div>
            <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Live</span>
          </div>

          <button
            onClick={onRefresh}
            disabled={isLoading}
            className="flex items-center gap-2 px-4 py-2 bg-white border border-slate-200 rounded-xl text-xs font-bold text-slate-600 hover:bg-slate-50 transition-all disabled:opacity-50 shadow-sm"
          >
            <i className={`fas fa-sync-alt ${isLoading ? 'animate-spin' : ''}`}></i>
            <span>{isLoading ? 'Refreshing...' : 'Refresh'}</span>
          </button>
        </div>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isLoading, onRefresh]);

  return null;
};

export default GcpCostHeader;
