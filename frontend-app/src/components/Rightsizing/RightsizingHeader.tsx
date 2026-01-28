/**
 * Rightsizing Header Component
 *
 * Displays the page header with title, icon, and refresh button
 */

import React from 'react';

interface RightsizingHeaderProps {
  isRefreshing: boolean;
  onRefresh: () => void;
}

const RightsizingHeader: React.FC<RightsizingHeaderProps> = ({ isRefreshing, onRefresh }) => {
  return (
    <header className="header-gradient h-20 flex items-center justify-between px-8 sticky top-0 z-20">
      <div className="flex items-center space-x-4">
        <div className="w-12 h-12 bg-gradient-to-br from-green-500 to-blue-600 rounded-xl flex items-center justify-center shadow-lg">
          <i className="fas fa-compress-arrows-alt text-white text-xl"></i>
        </div>
        <div>
          <h1 className="text-2xl font-bold text-gray-800">Rightsizing</h1>
          <p className="text-sm text-gray-600">Optimize your cloud resource sizing</p>
        </div>
      </div>
      <div className="flex items-center space-x-4">
        <button
          onClick={onRefresh}
          disabled={isRefreshing}
          className="btn-primary flex items-center space-x-2 px-6 py-3 text-sm font-semibold rounded-lg shadow-md disabled:opacity-60 disabled:cursor-not-allowed"
        >
          <i className={`fas fa-sync-alt ${isRefreshing ? 'fa-spin' : ''}`}></i>
          <span>{isRefreshing ? 'Analyzing...' : 'Refresh Analysis'}</span>
        </button>
      </div>
    </header>
  );
};

export default RightsizingHeader;
