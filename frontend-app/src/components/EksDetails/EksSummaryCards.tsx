/**
 * EKS Summary Cards Component
 *
 * Professional metric cards showing key cluster statistics
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

  // Helper to get status color for efficiency
  const getEfficiencyStatus = () => {
    if (costEfficiency >= 70) return { bg: 'bg-emerald-50', text: 'text-emerald-700', border: 'border-emerald-200', icon: 'check-circle', label: 'Optimized' };
    if (costEfficiency >= 50) return { bg: 'bg-amber-50', text: 'text-amber-700', border: 'border-amber-200', icon: 'exclamation-triangle', label: 'Can Improve' };
    return { bg: 'bg-red-50', text: 'text-red-700', border: 'border-red-200', icon: 'exclamation-circle', label: 'High Waste' };
  };

  const getCpuStatus = () => {
    const cpu = dashboard?.clusterOverview?.cpuUsagePercent || 0;
    if (cpu >= 80) return { border: 'border-red-200', icon: 'text-red-500' };
    if (cpu >= 60) return { border: 'border-amber-200', icon: 'text-amber-500' };
    return { border: 'border-slate-200', icon: 'text-slate-600' };
  };

  const getMemoryStatus = () => {
    const mem = dashboard?.clusterOverview?.memoryUsagePercent || 0;
    if (mem >= 80) return { border: 'border-red-200', icon: 'text-red-500' };
    if (mem >= 60) return { border: 'border-amber-200', icon: 'text-amber-500' };
    return { border: 'border-slate-200', icon: 'text-slate-600' };
  };

  const efficiencyStatus = getEfficiencyStatus();
  const cpuStatus = getCpuStatus();
  const memoryStatus = getMemoryStatus();
  const criticalEvents = dashboard?.securityMetrics?.eventsByPriority?.['4'] ||
    dashboard?.securityMetrics?.eventsByPriority?.['CRITICAL'] || 0;

  return (
    <div className="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-7 gap-4 mb-8">
      {/* Cost Today */}
      <div className="group bg-white rounded-xl p-5 border border-gray-200 hover:border-gray-300 hover:shadow-md transition-all">
        <div className="flex items-center gap-2 mb-3">
          <div className="w-8 h-8 rounded-lg bg-slate-100 flex items-center justify-center">
            <i className="fas fa-dollar-sign text-slate-600 text-sm"></i>
          </div>
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
            Cost Today
          </p>
        </div>
        <p className="text-2xl font-bold text-gray-900 mb-2">
          ${(dashboard?.costMetrics?.dailyCost || 0).toFixed(2)}
        </p>
        <div className="pt-2 border-t border-gray-100">
          <p className="text-xs text-gray-600">
            Monthly Est: <span className="font-semibold text-gray-900">${((dashboard?.costMetrics?.dailyCost || 0) * 30).toFixed(2)}</span>
          </p>
        </div>
      </div>

      {/* Efficiency */}
      <div className={`group bg-white rounded-xl p-5 border ${efficiencyStatus.border} hover:shadow-md transition-all`}>
        <div className="flex items-center gap-2 mb-3">
          <div className={`w-8 h-8 rounded-lg ${efficiencyStatus.bg} flex items-center justify-center`}>
            <i className={`fas fa-chart-line ${efficiencyStatus.text} text-sm`}></i>
          </div>
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
            Efficiency
          </p>
        </div>
        <p className="text-2xl font-bold text-gray-900 mb-2">{costEfficiency}%</p>
        <div className="pt-2 border-t border-gray-100">
          <div className={`inline-flex items-center gap-1.5 px-2 py-1 rounded-md ${efficiencyStatus.bg} ${efficiencyStatus.text}`}>
            <i className={`fas fa-${efficiencyStatus.icon} text-xs`}></i>
            <span className="text-xs font-semibold">{efficiencyStatus.label}</span>
          </div>
        </div>
      </div>

      {/* Security */}
      <div className={`group bg-white rounded-xl p-5 border ${criticalEvents > 0 ? 'border-red-200' : 'border-gray-200'} hover:shadow-md transition-all`}>
        <div className="flex items-center gap-2 mb-3">
          <div className={`w-8 h-8 rounded-lg ${criticalEvents > 0 ? 'bg-red-50' : 'bg-emerald-50'} flex items-center justify-center`}>
            <i className={`fas fa-shield-alt ${criticalEvents > 0 ? 'text-red-600' : 'text-emerald-600'} text-sm`}></i>
          </div>
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
            Security
          </p>
        </div>
        <p className="text-2xl font-bold text-gray-900 mb-2">
          {dashboard?.securityMetrics?.totalEvents || 0}
        </p>
        <div className="pt-2 border-t border-gray-100 flex items-center justify-between">
          <span className={`text-xs font-semibold ${criticalEvents > 0 ? 'text-red-600' : 'text-gray-600'}`}>
            {criticalEvents} Critical
          </span>
          <button
            onClick={() => setActiveTab('security')}
            className="text-gray-400 hover:text-gray-600 transition-colors"
          >
            <i className="fas fa-arrow-right text-xs"></i>
          </button>
        </div>
      </div>

      {/* NODES CARD */}
      <div className="group bg-white rounded-xl p-5 border border-gray-200 hover:border-gray-300 hover:shadow-md transition-all">
        <div className="flex items-center gap-2 mb-3">
          <div className="w-8 h-8 rounded-lg bg-blue-50 flex items-center justify-center">
            <i className="fas fa-server text-blue-600 text-sm"></i>
          </div>
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
            Nodes
          </p>
        </div>
        <p className="text-2xl font-bold text-gray-900 mb-2">
          {dashboard?.clusterOverview?.totalNodes || 0}
        </p>
        <div className="pt-2 border-t border-gray-100">
          <p className="text-xs text-gray-600">Compute fleet</p>
        </div>
      </div>

      {/* PODS CARD */}
      <div className="group bg-white rounded-xl p-5 border border-gray-200 hover:border-gray-300 hover:shadow-md transition-all">
        <div className="flex items-center gap-2 mb-3">
          <div className="w-8 h-8 rounded-lg bg-indigo-50 flex items-center justify-center">
            <i className="fas fa-cubes text-indigo-600 text-sm"></i>
          </div>
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
            Pods
          </p>
        </div>
        <p className="text-2xl font-bold text-gray-900 mb-2">{totalPodsCount}</p>
        <div className="pt-2 border-t border-gray-100">
          <p className="text-xs text-gray-600">
            <span className="font-semibold text-gray-900">
              {dashboard?.clusterOverview?.totalNamespaces || 0}
            </span>{' '}
            namespaces
          </p>
        </div>
      </div>

      {/* CPU CARD */}
      <div className={`group bg-white rounded-xl p-5 border ${cpuStatus.border} hover:shadow-md transition-all`}>
        <div className="flex items-center gap-2 mb-3">
          <div className="w-8 h-8 rounded-lg bg-slate-50 flex items-center justify-center">
            <i className={`fas fa-microchip ${cpuStatus.icon} text-sm`}></i>
          </div>
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
            CPU
          </p>
        </div>
        <p className="text-lg font-bold text-gray-900 mb-2">
          {Math.round(dashboard?.clusterOverview?.cpuUsageMillicores || 0)}m / {Math.round(dashboard?.clusterOverview?.cpuCapacityMillicores || 0)}m
        </p>
        <div className="pt-2 border-t border-gray-100">
          <div className="w-full h-1.5 bg-gray-100 rounded-full overflow-hidden">
            <div
              className="h-full bg-gradient-to-r from-slate-400 to-slate-600 rounded-full transition-all duration-1000"
              style={{
                width: `${Math.min(100, (dashboard?.clusterOverview?.cpuUsageMillicores || 0) / (dashboard?.clusterOverview?.cpuCapacityMillicores || 1) * 100)}%`
              }}
            ></div>
          </div>
        </div>
      </div>

      {/* MEMORY CARD */}
      <div className={`group bg-white rounded-xl p-5 border ${memoryStatus.border} hover:shadow-md transition-all`}>
        <div className="flex items-center gap-2 mb-3">
          <div className="w-8 h-8 rounded-lg bg-slate-50 flex items-center justify-center">
            <i className={`fas fa-memory ${memoryStatus.icon} text-sm`}></i>
          </div>
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
            Memory
          </p>
        </div>
        <p className="text-2xl font-bold text-gray-900 mb-2">
          {(dashboard?.clusterOverview?.memoryUsagePercent || 0).toFixed(0)}%
        </p>
        <div className="pt-2 border-t border-gray-100">
          <div className="w-full h-1.5 bg-gray-100 rounded-full overflow-hidden">
            <div
              className="h-full bg-gradient-to-r from-slate-400 to-slate-600 rounded-full transition-all duration-1000"
              style={{
                width: `${dashboard?.clusterOverview?.memoryUsagePercent || 0}%`
              }}
            ></div>
          </div>
          <p className="text-xs text-gray-500 mt-1.5">
            {((dashboard?.clusterOverview?.memoryUsageBytes || 0) / (1024 * 1024 * 1024)).toFixed(1)}GB / {((dashboard?.clusterOverview?.memoryCapacityBytes || 0) / (1024 * 1024 * 1024)).toFixed(1)}GB
          </p>
        </div>
      </div>
    </div>
  );
};

export default EksSummaryCards;
