/**
 * Dashboard Header Component
 *
 * Header for the Unified Command Center with refresh button and tour functionality
 */

import React from 'react';

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
  return (
    <header className="h-20 bg-gradient-to-r from-white via-gray-50 to-white border-b border-gray-200 flex items-center justify-between px-8 sticky top-0 z-20 shadow-lg backdrop-blur-sm">
      <div className="flex items-center gap-4">
        <div className="w-12 h-12 bg-gradient-to-br from-indigo-600 via-purple-600 to-indigo-700 rounded-xl flex items-center justify-center text-white shadow-lg transform hover:scale-105 transition-transform">
          <i className="fas fa-layer-group text-lg"></i>
        </div>
        <div>
          <h1 className="text-xl font-black text-gray-900 tracking-tight flex items-center gap-2">
            Unified Command Center
            <span className="text-xs font-semibold px-2 py-0.5 bg-gradient-to-r from-orange-100 to-amber-100 text-orange-600 rounded-full">
              beta
            </span>
          </h1>
          <div className="flex items-center gap-3 text-xs text-gray-500 mt-1">
            <div className="flex items-center gap-1.5">
              <div className="w-2 h-2 bg-green-500 rounded-full animate-pulse shadow-lg shadow-green-500/50"></div>
              <span className="font-medium">Live Overview</span>
            </div>
            <span className="text-gray-300">|</span>
            <span className="text-gray-400">Multi-Cloud Monitoring</span>
          </div>
        </div>
      </div>
      <div className="flex items-center gap-4">
        <div className="text-xs text-gray-500 flex items-center gap-2 bg-white px-3 py-2 rounded-lg border border-gray-200 shadow-sm">
          <i className="far fa-clock text-indigo-600"></i>
          <span>{serverTime ? `Synced: ${serverTime}` : lastUpdatedText}</span>
        </div>

        {showTourButton && onStartTour && (
          <button
            id="restart-tour-btn"
            onClick={onStartTour}
            className="flex items-center gap-2 px-4 py-2 text-sm font-semibold text-white bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-700 hover:to-purple-700 shadow-md hover:shadow-lg rounded-lg transition-all"
          >
            <i className="fas fa-rocket"></i> App Tour
          </button>
        )}

        <button
          id="tour-refresh-btn"
          onClick={onRefresh}
          disabled={isLoading}
          className="flex items-center gap-2 px-4 py-2 text-sm font-semibold text-white bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-700 hover:to-purple-700 shadow-md hover:shadow-lg rounded-lg transition-all active:scale-95 transform disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <i className={`fas fa-sync-alt ${isLoading ? 'animate-spin' : ''}`}></i>
          Refresh
        </button>
      </div>
    </header>
  );
};
