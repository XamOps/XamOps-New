/**
 * GCP CloudK8s Header Component
 *
 * Header section for GCP CloudK8s (GKE) dashboard
 */

import React from 'react';

interface GcpCloudK8sHeaderProps {
  isRefreshing: boolean;
  onRefresh: () => void;
}

const GcpCloudK8sHeader: React.FC<GcpCloudK8sHeaderProps> = ({
  isRefreshing,
  onRefresh
}) => {
  return (
    <header className="header-gradient h-20 flex items-center justify-between px-8 sticky top-0 z-20">
      <div className="flex items-center space-x-4">
        <div className="w-12 h-12 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-xl flex items-center justify-center">
          <i className="fab fa-google text-white text-xl"></i>
        </div>
        <div>
          <h1 className="text-2xl font-bold text-gray-800">GKE Dashboard</h1>
          <p className="text-sm text-gray-600">Monitor your Google Kubernetes Engine clusters</p>
        </div>
      </div>
      <button
        onClick={onRefresh}
        disabled={isRefreshing}
        className="px-4 py-2 text-sm font-semibold rounded-lg bg-white border text-black hover:bg-gray-50 flex items-center transition-colors disabled:opacity-50"
      >
        <i className={`fas fa-sync-alt mr-2 ${isRefreshing ? 'fa-spin' : ''}`}></i>
        <span>{isRefreshing ? 'Refreshing...' : 'Refresh Now'}</span>
      </button>
    </header>
  );
};

export default GcpCloudK8sHeader;
