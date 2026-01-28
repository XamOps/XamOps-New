/**
 * CostHeader Component
 * Header with title, live data indicator, and refresh button
 */

import React from 'react';

interface CostHeaderProps {
  isLoading: boolean;
  onRefresh: () => void;
}

const CostHeader: React.FC<CostHeaderProps> = ({ isLoading, onRefresh }) => {
  return (
    <header className="bg-white/95 backdrop-blur-md border-b border-gray-200 h-20 flex items-center justify-between px-8 sticky top-0 z-10 transition-all duration-300">
      <div className="flex items-center space-x-4">
        <div className="w-12 h-12 bg-gradient-to-br from-green-500 to-emerald-600 rounded-xl flex items-center justify-center shadow-lg shadow-green-500/20">
          <i className="fas fa-dollar-sign text-white text-xl"></i>
        </div>
        <div>
          <h1 className="text-2xl font-bold text-gray-800">Cost Management</h1>
          <p className="text-sm text-gray-600">Analyze and optimize your cloud spending</p>
        </div>
      </div>
      <div className="flex items-center space-x-2">
        <span className="flex items-center text-sm text-green-600 bg-green-50 px-3 py-1 rounded-full border border-green-100">
          <span className="w-2 h-2 bg-green-500 rounded-full mr-2 animate-pulse"></span>
          Live data
        </span>
        <button
          onClick={onRefresh}
          className="px-4 py-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 transition shadow-md hover:shadow-lg flex items-center"
        >
          <i className={`fas fa-sync-alt mr-2 ${isLoading ? 'animate-spin' : ''}`}></i>
          Refresh Data
        </button>
      </div>
    </header>
  );
};

export default CostHeader;
