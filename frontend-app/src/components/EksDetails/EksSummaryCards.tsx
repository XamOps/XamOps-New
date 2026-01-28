/**
 * EKS Summary Cards Component
 *
 * Compact metric cards showing key cluster statistics
 */

import React from 'react';

interface EksSummaryCardsProps {
  eksData: any;
}

const EksSummaryCards: React.FC<EksSummaryCardsProps> = ({ eksData }) => {
  const {
    dashboard,
    costEfficiency,
    idleCostPercentage,
    totalPodsCount,
    setActiveTab
  } = eksData;

  return (
    <div className="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-7 gap-3 mb-6">
      {/* Cost Today */}
      <div className="p-3 bg-gradient-to-br from-blue-50 to-white rounded-lg border border-blue-100 hover:shadow-md transition-shadow">
        <p className="text-[10px] font-semibold text-blue-600 uppercase tracking-wide mb-1 flex items-center gap-1.5">
          <i className="fas fa-dollar-sign text-xs"></i>
          Cost Today
        </p>
        <p className="text-2xl font-bold text-gray-900">
          ${(dashboard?.costMetrics?.dailyCost || 0).toFixed(2)}
        </p>
        <p className="text-xs text-gray-500 font-medium mt-1">
          Monthly: <span className="text-gray-900 font-bold">${((dashboard?.costMetrics?.dailyCost || 0) * 30).toFixed(2)}</span>
        </p>
      </div>

      {/* Efficiency */}
      <div className="p-3 bg-gradient-to-br from-green-50 to-white rounded-lg border border-green-100 hover:shadow-md transition-shadow">
        <p className="text-[10px] font-semibold text-green-600 uppercase tracking-wide mb-1 flex items-center gap-1.5">
          <i className="fas fa-chart-line text-xs"></i>
          Efficiency
        </p>
        <p className="text-2xl font-bold text-gray-900">{costEfficiency}%</p>
        <p
          className={`text-xs font-medium mt-1 ${
            costEfficiency >= 70
              ? 'text-green-600'
              : costEfficiency >= 50
              ? 'text-amber-600'
              : 'text-red-600'
          }`}
        >
          {costEfficiency >= 70 && <span>Optimized</span>}
          {costEfficiency >= 50 && costEfficiency < 70 && (
            <span>
              Save ${((dashboard?.costMetrics?.dailyCost || 0) * (idleCostPercentage / 100)).toFixed(2)}/day
            </span>
          )}
          {costEfficiency < 50 && <span>High waste</span>}
        </p>
      </div>

      {/* Security */}
      <div className="p-3 bg-gradient-to-br from-red-50 to-white rounded-lg border border-red-100 hover:shadow-md transition-shadow">
        <p className="text-[10px] font-semibold text-red-600 uppercase tracking-wide mb-1 flex items-center gap-1.5">
          <i className="fas fa-shield-alt text-xs"></i>
          Security
        </p>
        <p className="text-2xl font-bold text-gray-900">
          {dashboard?.securityMetrics?.totalEvents || 0}
        </p>
        <p className="text-xs text-red-600 font-medium mt-1">
          <span>
            {dashboard?.securityMetrics?.eventsByPriority?.['4'] ||
              dashboard?.securityMetrics?.eventsByPriority?.['CRITICAL'] ||
              0}
          </span>{' '}
          Critical |{' '}
          <button
            onClick={() => setActiveTab('security')}
            className="text-blue-600 hover:text-blue-700 font-semibold"
          >
            View →
          </button>
        </p>
      </div>

      {/* NODES CARD */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-100 p-3 hover:shadow-md transition-all group">
        <div className="flex items-center justify-between mb-3">
          <div className="w-10 h-10 bg-blue-50 rounded-lg flex items-center justify-center">
            <i className="fas fa-server text-blue-600 text-lg"></i>
          </div>
        </div>
        <h3 className="text-[10px] font-semibold uppercase tracking-wider text-gray-500 mb-1">
          Active Nodes
        </h3>
        <p className="text-2xl font-black text-gray-900 mb-1">
          {dashboard?.clusterOverview?.totalNodes || 0}
        </p>
        <div className="text-xs text-gray-600">Compute resources</div>
      </div>

      {/* PODS CARD */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-100 p-3 hover:shadow-md transition-all group">
        <div className="flex items-center justify-between mb-3">
          <div className="w-10 h-10 bg-purple-50 rounded-lg flex items-center justify-center">
            <i className="fas fa-cubes text-purple-600 text-lg"></i>
          </div>
        </div>
        <h3 className="text-[10px] font-semibold uppercase tracking-wider text-gray-500 mb-1">
          Total Pods
        </h3>
        <p className="text-2xl font-black text-gray-900 mb-1">{totalPodsCount}</p>
        <div className="text-xs text-gray-600">
          <span className="font-semibold text-gray-900">
            {dashboard?.clusterOverview?.totalNamespaces || 0}
          </span>{' '}
          namespaces
        </div>
      </div>

      {/* CPU CARD */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-100 p-3 hover:shadow-md transition-all group">
        <div className="flex items-center justify-between mb-3">
          <div className="w-10 h-10 bg-orange-50 rounded-lg flex items-center justify-center">
            <i className="fas fa-microchip text-orange-600 text-lg"></i>
          </div>
        </div>
        <h3 className="text-[10px] font-semibold uppercase tracking-wider text-gray-500 mb-1">
          Cluster CPU Usage
        </h3>
        <p className="text-2xl font-black text-gray-900 mb-1">
          {(dashboard?.clusterOverview?.cpuUsageMillicores || 0).toFixed(0)}mc /{' '}
          {(dashboard?.clusterOverview?.cpuCapacityMillicores || 0).toFixed(0)}mc
        </p>
        <p className="text-xs text-gray-500 mb-2">total capacity</p>
        <div className="flex items-center gap-2">
          <div className="flex-1 h-1.5 bg-gray-100 rounded-full overflow-hidden">
            <div
              className="h-full bg-gradient-to-r from-orange-400 to-orange-500 rounded-full transition-all duration-1000"
              style={{
                width: `${Math.min(100, dashboard?.clusterOverview?.cpuUsagePercent || 0)}%`
              }}
            ></div>
          </div>
          <span className="text-[10px] font-semibold text-gray-500">USAGE</span>
        </div>
      </div>

      {/* MEMORY CARD */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-100 p-3 hover:shadow-md transition-all group">
        <div className="flex items-center justify-between mb-3">
          <div className="w-10 h-10 bg-pink-50 rounded-lg flex items-center justify-center">
            <i className="fas fa-memory text-pink-600 text-lg"></i>
          </div>
        </div>
        <h3 className="text-[10px] font-semibold uppercase tracking-wider text-gray-500 mb-1">
          Cluster Memory Usage
        </h3>
        <p className="text-2xl font-black text-gray-900 mb-2">
          {(dashboard?.clusterOverview?.memoryUsagePercent || 0).toFixed(1)}%
        </p>
        <div className="flex items-center gap-2">
          <div className="flex-1 h-1.5 bg-gray-100 rounded-full overflow-hidden">
            <div
              className="h-full bg-gradient-to-r from-pink-400 to-pink-500 rounded-full transition-all duration-1000"
              style={{
                width: `${dashboard?.clusterOverview?.memoryUsagePercent || 0}%`
              }}
            ></div>
          </div>
          <span className="text-[10px] font-semibold text-gray-500">PRESSURE</span>
        </div>
      </div>
    </div>
  );
};

export default EksSummaryCards;
