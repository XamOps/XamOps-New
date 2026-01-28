/**
 * EKS Performance Tab - Placeholder
 */

import React from 'react';

const EksPerformanceTab: React.FC<{ eksData: any }> = ({ eksData }) => {
  const { dashboard } = eksData;

  return (
    <div className="bg-white rounded-xl shadow-sm p-8 border border-gray-100">
      <h3 className="text-xl font-bold text-gray-900 mb-4">Performance Metrics</h3>
      <p className="text-gray-600">Performance monitoring coming soon...</p>
      <div className="mt-6 grid grid-cols-2 md:grid-cols-4 gap-4">
        <div className="p-4 bg-gray-50 rounded-lg text-center">
          <div className="text-2xl font-bold text-gray-900">
            {(dashboard?.clusterOverview?.cpuUsagePercent || 0).toFixed(1)}%
          </div>
          <div className="text-sm text-gray-600 mt-1">CPU Usage</div>
        </div>
        <div className="p-4 bg-gray-50 rounded-lg text-center">
          <div className="text-2xl font-bold text-gray-900">
            {(dashboard?.clusterOverview?.memoryUsagePercent || 0).toFixed(1)}%
          </div>
          <div className="text-sm text-gray-600 mt-1">Memory Usage</div>
        </div>
      </div>
    </div>
  );
};

export default EksPerformanceTab;
