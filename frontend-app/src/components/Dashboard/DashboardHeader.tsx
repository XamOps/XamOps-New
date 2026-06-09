import React from 'react';
import { useTopbar } from '../../contexts/TopbarContext';

interface DashboardHeaderProps {
  lastUpdatedText: string;
  serverTime: string | null;
  isLoading: boolean;
  onRefresh: () => void;
  onStartTour?: () => void;
  showTourButton?: boolean;
}

export const DashboardHeader: React.FC<DashboardHeaderProps> = ({
  lastUpdatedText,
  serverTime,
  isLoading,
  onRefresh,
  onStartTour,
  showTourButton = false,
}) => {
  const { setActions, clearActions } = useTopbar();

  // Register dashboard actions in Topbar
  React.useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        {/* Sync Status */}
        <div className="flex items-center gap-2 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-xl px-3 py-1.5">
          <i className="far fa-clock text-[rgba(0,0,0,0.4)] text-xs"></i>
          <span className="text-xs font-medium text-[rgba(0,0,0,0.5)]">
            {serverTime ? `Synced: ${serverTime}` : lastUpdatedText}
          </span>
        </div>

        {/* Tour Button */}
        {showTourButton && onStartTour && (
          <button
            onClick={onStartTour}
            className="flex items-center gap-1.5 text-[12px] font-medium text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors"
          >
            <i className="fas fa-rocket text-[10px]"></i> App Tour
          </button>
        )}

        {/* Refresh Button */}
        <button
          onClick={onRefresh}
          disabled={isLoading}
          className="flex items-center gap-1.5 text-[12px] font-medium text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors disabled:opacity-40"
        >
          <i className={`fas fa-sync-alt text-[10px] ${isLoading ? 'animate-spin' : ''}`} />
          <span>{isLoading ? 'Refreshing...' : 'Refresh'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [lastUpdatedText, serverTime, isLoading, onRefresh, onStartTour, showTourButton, setActions, clearActions]);

  return null;
};
