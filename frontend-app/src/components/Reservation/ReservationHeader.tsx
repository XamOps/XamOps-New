import React, { useEffect } from 'react';
import { Icon } from '@iconify/react';
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
          className="flex items-center gap-2 px-4 py-2 bg-white border border-[rgba(0,0,0,0.12)] rounded-md text-xs font-bold text-[rgba(0,0,0,0.7)] hover:bg-[rgba(0,0,0,0.025)] transition-all disabled:opacity-50 shadow-sm"
        >
          <Icon icon="mdi:refresh" className={isLoading ? 'animate-spin' : ''} width={14} />
          <span>{isLoading ? 'Syncing...' : 'Refresh'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, selectedAccountId, isLoading, onRefresh]);

  return null;
};

export default ReservationHeader;
