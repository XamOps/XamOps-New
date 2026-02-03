import React, { useEffect } from 'react';
import { useTopbar } from '../../contexts/TopbarContext';

interface GcpWasteHeaderProps {
  isRefreshing: boolean;
  onRefresh: () => void;
}

const GcpWasteHeader: React.FC<GcpWasteHeaderProps> = ({ isRefreshing, onRefresh }) => {
  const { setActions, clearActions } = useTopbar();

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
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

export default GcpWasteHeader;
