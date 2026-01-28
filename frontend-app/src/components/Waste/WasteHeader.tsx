/**
 * WasteHeader Component
 *
 * Header section for the Waste Management page with title and refresh button
 */

import React from 'react';

interface WasteHeaderProps {
  isRefreshing: boolean;
  onRefresh: () => void;
}

const WasteHeader: React.FC<WasteHeaderProps> = ({ isRefreshing, onRefresh }) => {
  return (
    <header className="header-gradient h-20 flex items-center justify-between px-8 sticky top-0 z-10">
      <div className="flex items-center space-x-4">
        <div className="w-12 h-12 bg-gradient-to-br from-red-500 to-pink-600 rounded-xl flex items-center justify-center shadow-lg">
          <i className="fas fa-recycle text-white text-xl"></i>
        </div>
        <div>
          <h1 className="text-2xl font-bold text-gray-800">Waste Management</h1>
          <p className="text-sm text-gray-600">Identify and eliminate unused cloud resources</p>
        </div>
      </div>
      <div className="flex items-center space-x-4">
        <div className="flex items-center space-x-2">
          <div className="w-2 h-2 bg-red-500 rounded-full animate-pulse"></div>
          <span className="text-sm text-gray-600">Waste detected</span>
        </div>
        <button
          onClick={onRefresh}
          disabled={isRefreshing}
          className="btn-primary flex items-center space-x-2 px-6 py-3 text-sm font-semibold rounded-lg shadow-md disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <i className={`fas fa-sync-alt ${isRefreshing ? 'fa-spin' : ''}`}></i>
          <span>{isRefreshing ? 'Scanning...' : 'Scan Resources'}</span>
        </button>
      </div>
    </header>
  );
};

export default WasteHeader;
