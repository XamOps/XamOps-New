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
        <div className="flex items-center gap-2 bg-slate-50 border border-slate-200 rounded-xl px-4 py-2 shadow-sm">
          <i className="far fa-clock text-indigo-600 text-xs"></i>
          <span className="text-xs font-medium text-slate-500">
            {serverTime ? `Synced: ${serverTime}` : lastUpdatedText}
          </span>
        </div>

        {/* Tour Button */}
        {showTourButton && onStartTour && (
          <button
            onClick={onStartTour}
            className="flex items-center gap-2 px-4 py-2 text-sm font-semibold text-white bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-700 hover:to-purple-700 shadow-md hover:shadow-lg rounded-xl transition-all"
          >
            <i className="fas fa-rocket"></i> App Tour
          </button>
        )}

        {/* Refresh Button */}
        <button
          onClick={onRefresh}
          disabled={isLoading}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-sm font-semibold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50"
        >
          <i className={`fas fa-sync-alt ${isLoading ? 'fa-spin' : ''}`} />
          <span>{isLoading ? 'Refreshing...' : 'Refresh'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [lastUpdatedText, serverTime, isLoading, onRefresh, onStartTour, showTourButton, setActions, clearActions]);

  return null;
};
