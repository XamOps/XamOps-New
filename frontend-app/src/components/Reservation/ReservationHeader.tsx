import React, { useEffect } from 'react';
import { useTopbar } from '../../contexts/TopbarContext';

interface ReservationHeaderProps {
  selectedAccountId: string | null;
  isLoading: boolean;
  onRefresh: () => void;
}

const ReservationHeader: React.FC<ReservationHeaderProps> = ({
  selectedAccountId,
  isLoading,
  onRefresh,
}) => {
  const { setActions, clearActions } = useTopbar();

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        {selectedAccountId && (
          <span className="px-3 py-1 bg-slate-100 text-slate-600 text-[10px] font-bold font-mono rounded-lg border border-slate-200">
            {selectedAccountId}
          </span>
        )}
        <button
          onClick={onRefresh}
          disabled={isLoading}
          className="flex items-center gap-2 px-4 py-2 bg-white border border-slate-200 rounded-xl text-xs font-bold text-slate-600 hover:bg-slate-50 transition-all disabled:opacity-50 shadow-sm"
        >
          <i className={`fas fa-sync-alt ${isLoading ? 'animate-spin' : ''}`}></i>
          <span>{isLoading ? 'Syncing...' : 'Refresh'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, selectedAccountId, isLoading, onRefresh]);

  return null;
};

export default ReservationHeader;
