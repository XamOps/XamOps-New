import React, { useEffect } from 'react';
import { useTopbar } from '../../contexts/TopbarContext';

interface AzureReservationHeaderProps {
  selectedAccountId: string | null;
  isLoading: boolean;
  onRefresh: () => void;
}

const AzureReservationHeader: React.FC<AzureReservationHeaderProps> = ({
  selectedAccountId,
  isLoading,
  onRefresh,
}) => {
  const { setActions, clearActions } = useTopbar();

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-1.5 bg-amber-50 border border-amber-200 text-amber-700 text-xs font-semibold px-3 py-1.5 rounded-full">
          <i className="fas fa-shield-alt text-xs"></i>
          <span>Requires Reservation Reader role</span>
        </div>
        {selectedAccountId && (
          <button
            onClick={onRefresh}
            disabled={isLoading}
            className="flex items-center gap-2 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-xs font-bold rounded-xl transition-all disabled:opacity-50 active:scale-95"
          >
            <i className={`fas fa-sync-alt text-xs ${isLoading ? 'animate-spin' : ''}`}></i>
            <span>{isLoading ? 'Loading...' : 'Refresh'}</span>
          </button>
        )}
      </div>
    );
    return () => clearActions();
  }, [selectedAccountId, isLoading, onRefresh, setActions, clearActions]);

  return null;
};

export default AzureReservationHeader;
