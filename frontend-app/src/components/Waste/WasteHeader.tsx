import React, { useEffect } from 'react';
import { useTopbar } from '../../contexts/TopbarContext';

interface WasteHeaderProps {
  isRefreshing: boolean;
  onRefresh: () => void;
}

const WasteHeader: React.FC<WasteHeaderProps> = ({ isRefreshing, onRefresh }) => {
  const { setActions, clearActions } = useTopbar();

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        {/* Status */}
        <div className="flex items-center gap-2 px-3 py-1.5 bg-rose-50 rounded-lg border border-rose-200">
          <div className="w-1.5 h-1.5 bg-rose-500 rounded-full animate-pulse shadow-[0_0_8px_rgba(244,63,94,0.4)]"></div>
          <span className="text-[10px] font-bold text-rose-600 uppercase tracking-wide">Waste detected</span>
        </div>

        {/* Action Button */}
        <button
          onClick={onRefresh}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-4 py-2 bg-rose-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-rose-200 hover:bg-rose-700 transition-all disabled:opacity-50 active:scale-95"
        >
          <i className={`fas fa-sync-alt ${isRefreshing ? 'animate-spin' : ''}`}></i>
          <span>{isRefreshing ? 'Scanning...' : 'Scan Resources'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isRefreshing, onRefresh]);

  return null;
};

export default WasteHeader;
