/**
 * GKE Overview Tab
 *
 * Shows cluster health score, resource metrics, and cluster metadata.
 */

import React from 'react';

const GkeOverviewTab: React.FC<{ gkeData: any }> = ({ gkeData }) => {
  const { dashboard } = gkeData;
  const overview = dashboard?.overview;

  const healthScore = dashboard?.workloadHealth
    ? Math.round(dashboard.workloadHealth.clusterHealthScore)
    : null;

  const cpuPct = Math.round(overview?.cpuUsagePercent ?? 0);
  const memPct = Math.round(overview?.memoryUsagePercent ?? 0);

  const getHealthColor = (score: number) => {
    if (score >= 90) return { ring: 'stroke-emerald-500', label: 'Excellent', text: 'text-emerald-600', bg: 'bg-emerald-50' };
    if (score >= 70) return { ring: 'stroke-blue-500', label: 'Good', text: 'text-blue-600', bg: 'bg-blue-50' };
    if (score >= 50) return { ring: 'stroke-amber-500', label: 'Fair', text: 'text-amber-600', bg: 'bg-amber-50' };
    return { ring: 'stroke-red-500', label: 'Poor', text: 'text-red-600', bg: 'bg-red-50' };
  };

  const colors = healthScore !== null ? getHealthColor(healthScore) : null;
  const circumference = 2 * Math.PI * 54;
  const dashOffset = healthScore !== null ? circumference * (1 - healthScore / 100) : circumference;

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">

        {/* Health Score */}
        <div className="bg-white rounded-xl border border-gray-200 p-6 flex flex-col items-center">
          <h3 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-4">Cluster Health</h3>
          {healthScore !== null && colors ? (
            <>
              <div className="relative w-36 h-36">
                <svg viewBox="0 0 120 120" className="w-full h-full -rotate-90">
                  <circle cx="60" cy="60" r="54" fill="none" stroke="#e5e7eb" strokeWidth="10" />
                  <circle
                    cx="60" cy="60" r="54" fill="none"
                    className={colors.ring}
                    strokeWidth="10"
                    strokeLinecap="round"
                    strokeDasharray={circumference}
                    strokeDashoffset={dashOffset}
                    style={{ transition: 'stroke-dashoffset 1s ease' }}
                  />
                </svg>
                <div className="absolute inset-0 flex flex-col items-center justify-center">
                  <span className="text-3xl font-bold text-gray-900">{healthScore}</span>
                  <span className="text-xs text-gray-500">/ 100</span>
                </div>
              </div>
              <span className={`mt-3 px-3 py-1 rounded-full text-xs font-bold ${colors.bg} ${colors.text}`}>
                {colors.label}
              </span>
            </>
          ) : (
            <div className="text-center py-8">
              <i className="fas fa-plug text-gray-300 text-4xl mb-3"></i>
              <p className="text-sm text-gray-500">Connect monitoring</p>
              <p className="text-xs text-gray-400 mt-1">to see health score</p>
            </div>
          )}
        </div>

        {/* Resource Metrics */}
        <div className="bg-white rounded-xl border border-gray-200 p-6">
          <h3 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-4">Resource Usage</h3>
          {overview ? (
            <div className="space-y-5">
              <div>
                <div className="flex justify-between text-sm mb-1.5">
                  <span className="font-medium text-gray-700">CPU</span>
                  <span className="font-bold text-gray-900">{cpuPct}%</span>
                </div>
                <div className="w-full h-2 bg-gray-100 rounded-full overflow-hidden">
                  <div
                    className={`h-full rounded-full ${cpuPct >= 80 ? 'bg-red-500' : cpuPct >= 60 ? 'bg-amber-500' : 'bg-indigo-500'}`}
                    style={{ width: `${Math.min(100, cpuPct)}%` }}
                  />
                </div>
                <p className="text-xs text-gray-400 mt-1">
                  {Math.round(overview.cpuUsageMillicores)}m / {Math.round(overview.cpuCapacityMillicores)}m
                </p>
              </div>
              <div>
                <div className="flex justify-between text-sm mb-1.5">
                  <span className="font-medium text-gray-700">Memory</span>
                  <span className="font-bold text-gray-900">{memPct}%</span>
                </div>
                <div className="w-full h-2 bg-gray-100 rounded-full overflow-hidden">
                  <div
                    className={`h-full rounded-full ${memPct >= 80 ? 'bg-red-500' : memPct >= 60 ? 'bg-amber-500' : 'bg-indigo-500'}`}
                    style={{ width: `${Math.min(100, memPct)}%` }}
                  />
                </div>
                <p className="text-xs text-gray-400 mt-1">
                  {(overview.memoryUsageBytes / 1073741824).toFixed(1)}GB / {(overview.memoryCapacityBytes / 1073741824).toFixed(1)}GB
                </p>
              </div>
            </div>
          ) : (
            <div className="text-center py-8 text-gray-400">
              <i className="fas fa-plug text-3xl mb-3"></i>
              <p className="text-sm">Connect monitoring to see metrics</p>
            </div>
          )}
        </div>

        {/* Cluster Metadata */}
        <div className="bg-white rounded-xl border border-gray-200 p-6">
          <h3 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-4">Cluster Info</h3>
          <dl className="space-y-3">
            {[
              { label: 'Name', value: dashboard?.clusterName },
              { label: 'Location', value: dashboard?.location },
              { label: 'Status', value: dashboard?.status },
              { label: 'K8s Version', value: dashboard?.version },
              { label: 'Endpoint', value: dashboard?.endpoint ? `${dashboard.endpoint.substring(0, 16)}…` : '—' },
              { label: 'Nodes', value: overview?.totalNodes ?? '—' },
              { label: 'Namespaces', value: overview?.totalNamespaces ?? '—' },
            ].map(({ label, value }) => (
              <div key={label} className="flex justify-between items-center text-sm">
                <dt className="text-gray-500">{label}</dt>
                <dd className="font-semibold text-gray-800 text-right max-w-[60%] truncate">{value ?? '—'}</dd>
              </div>
            ))}
          </dl>
        </div>
      </div>

      {/* Workload Summary (if connected) */}
      {dashboard?.workloadHealth && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          {[
            { label: 'Deployments', value: dashboard.workloadHealth.deployments?.length ?? 0, icon: 'fa-layer-group', color: 'bg-blue-50 text-blue-600' },
            { label: 'StatefulSets', value: dashboard.workloadHealth.statefulsets?.length ?? 0, icon: 'fa-database', color: 'bg-indigo-50 text-indigo-600' },
            { label: 'DaemonSets', value: dashboard.workloadHealth.daemonsets?.length ?? 0, icon: 'fa-network-wired', color: 'bg-teal-50 text-teal-600' },
            { label: 'Pods', value: dashboard.workloadHealth.pods?.length ?? 0, icon: 'fa-box', color: 'bg-purple-50 text-purple-600' },
          ].map(({ label, value, icon, color }) => (
            <div key={label} className="bg-white rounded-xl border border-gray-200 p-5">
              <div className="flex items-center gap-3 mb-2">
                <div className={`w-10 h-10 rounded-lg flex items-center justify-center ${color}`}>
                  <i className={`fas ${icon}`}></i>
                </div>
                <span className="text-xs font-semibold text-gray-500 uppercase tracking-wide">{label}</span>
              </div>
              <div className="text-2xl font-bold text-gray-900">{value}</div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default GkeOverviewTab;
