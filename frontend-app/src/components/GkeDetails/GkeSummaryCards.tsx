/**
 * GKE Summary Cards
 *
 * Top-level metric cards for the GKE cluster details page.
 */

import React from 'react';

const GkeSummaryCards: React.FC<{ gkeData: any }> = ({ gkeData }) => {
  const { dashboard, totalPodsCount } = gkeData;
  const overview = dashboard?.overview;

  const cpuPct = overview?.cpuUsagePercent ?? 0;
  const memPct = overview?.memoryUsagePercent ?? 0;

  const getCpuBorder = () => cpuPct >= 80 ? 'border-red-200' : cpuPct >= 60 ? 'border-amber-200' : 'border-gray-200';
  const getMemBorder = () => memPct >= 80 ? 'border-red-200' : memPct >= 60 ? 'border-amber-200' : 'border-gray-200';

  return (
    <div className="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-5 gap-4 mb-2">
      {/* Cluster Status */}
      <div className="bg-white rounded-xl p-5 border border-gray-200 hover:shadow-md transition-all">
        <div className="flex items-center gap-2 mb-3">
          <div className="w-8 h-8 rounded-lg bg-green-50 flex items-center justify-center">
            <i className="fas fa-circle-check text-green-600 text-sm"></i>
          </div>
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Status</p>
        </div>
        <p className="text-lg font-bold text-gray-900 mb-2 capitalize">
          {dashboard?.status?.toLowerCase() || '—'}
        </p>
        <div className="pt-2 border-t border-gray-100">
          <p className="text-xs text-gray-500">{dashboard?.location || '—'}</p>
        </div>
      </div>

      {/* Nodes */}
      <div className="bg-white rounded-xl p-5 border border-gray-200 hover:shadow-md transition-all">
        <div className="flex items-center gap-2 mb-3">
          <div className="w-8 h-8 rounded-lg bg-blue-50 flex items-center justify-center">
            <i className="fas fa-server text-blue-600 text-sm"></i>
          </div>
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Nodes</p>
        </div>
        <p className="text-2xl font-bold text-gray-900 mb-2">{overview?.totalNodes ?? '—'}</p>
        <div className="pt-2 border-t border-gray-100">
          <p className="text-xs text-gray-500">{dashboard?.nodePools?.length ?? 0} node pool(s)</p>
        </div>
      </div>

      {/* Pods */}
      <div className="bg-white rounded-xl p-5 border border-gray-200 hover:shadow-md transition-all">
        <div className="flex items-center gap-2 mb-3">
          <div className="w-8 h-8 rounded-lg bg-indigo-50 flex items-center justify-center">
            <i className="fas fa-cubes text-indigo-600 text-sm"></i>
          </div>
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Pods</p>
        </div>
        <p className="text-2xl font-bold text-gray-900 mb-2">{totalPodsCount || overview?.totalPods || '—'}</p>
        <div className="pt-2 border-t border-gray-100">
          <p className="text-xs text-gray-500">{overview?.totalNamespaces ?? 0} namespaces</p>
        </div>
      </div>

      {/* CPU */}
      <div className={`bg-white rounded-xl p-5 border ${getCpuBorder()} hover:shadow-md transition-all`}>
        <div className="flex items-center gap-2 mb-3">
          <div className="w-8 h-8 rounded-lg bg-slate-50 flex items-center justify-center">
            <i className="fas fa-microchip text-slate-600 text-sm"></i>
          </div>
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">CPU</p>
        </div>
        <p className="text-lg font-bold text-gray-900 mb-2">
          {overview
            ? `${Math.round(overview.cpuUsageMillicores)}m / ${Math.round(overview.cpuCapacityMillicores)}m`
            : '—'}
        </p>
        <div className="pt-2 border-t border-gray-100">
          <div className="w-full h-1.5 bg-gray-100 rounded-full overflow-hidden">
            <div
              className="h-full bg-gradient-to-r from-indigo-400 to-indigo-600 rounded-full"
              style={{ width: `${Math.min(100, cpuPct)}%` }}
            />
          </div>
        </div>
      </div>

      {/* Memory */}
      <div className={`bg-white rounded-xl p-5 border ${getMemBorder()} hover:shadow-md transition-all`}>
        <div className="flex items-center gap-2 mb-3">
          <div className="w-8 h-8 rounded-lg bg-slate-50 flex items-center justify-center">
            <i className="fas fa-memory text-slate-600 text-sm"></i>
          </div>
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Memory</p>
        </div>
        <p className="text-2xl font-bold text-gray-900 mb-2">
          {overview ? `${memPct.toFixed(0)}%` : '—'}
        </p>
        <div className="pt-2 border-t border-gray-100">
          <div className="w-full h-1.5 bg-gray-100 rounded-full overflow-hidden">
            <div
              className="h-full bg-gradient-to-r from-indigo-400 to-indigo-600 rounded-full"
              style={{ width: `${Math.min(100, memPct)}%` }}
            />
          </div>
          {overview && (
            <p className="text-xs text-gray-500 mt-1">
              {(overview.memoryUsageBytes / 1073741824).toFixed(1)}GB / {(overview.memoryCapacityBytes / 1073741824).toFixed(1)}GB
            </p>
          )}
        </div>
      </div>
    </div>
  );
};

export default GkeSummaryCards;
