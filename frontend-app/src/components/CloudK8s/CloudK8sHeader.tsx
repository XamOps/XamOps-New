/**
 * CloudK8s Header Component
 *
 * Displays the page header with title and refresh button
 */

import React from 'react';

interface CloudK8sHeaderProps {
  isRefreshing: boolean;
  onRefresh: () => void;
}

const CloudK8sHeader: React.FC<CloudK8sHeaderProps> = ({ isRefreshing, onRefresh }) => {
  return (
    <header className="cloudk8s-header h-20 flex items-center justify-between px-8 sticky top-0 z-20">
      <div className="flex items-center space-x-4">
        <div className="w-12 h-12 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-xl flex items-center justify-center shadow-lg">
          <i className="fas fa-dharmachakra text-white text-xl"></i>
        </div>
        <div>
          <h1 className="text-2xl font-bold text-gray-800">CloudK8s Dashboard</h1>
          <p className="text-sm text-gray-600">Monitor your EKS clusters and workloads</p>
        </div>
      </div>

      <button
        onClick={onRefresh}
        disabled={isRefreshing}
        className="px-5 py-2.5 text-sm font-semibold rounded-lg bg-white border border-gray-200 text-gray-700 hover:bg-gray-50 hover:border-gray-300 flex items-center transition-all disabled:opacity-50 disabled:cursor-not-allowed shadow-sm hover:shadow"
      >
        <i className={`fas fa-sync-alt mr-2 ${isRefreshing ? 'fa-spin' : ''}`}></i>
        <span>{isRefreshing ? 'Refreshing...' : 'Refresh Now'}</span>
      </button>
    </header>
  );
};

export default CloudK8sHeader;
