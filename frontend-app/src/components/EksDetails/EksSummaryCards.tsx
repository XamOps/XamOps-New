/**
 * EKS Summary Cards Component
 *
 * Cast.ai / ScaleOps premium hero strip with KPI pills and action cards
 */

import React from 'react';

interface EksSummaryCardsProps {
  eksData: any;
}

const EksSummaryCards: React.FC<EksSummaryCardsProps> = ({ eksData }) => {
  const {
    dashboard,
    costEfficiency,
    totalPodsCount,
    clusterHealthScore,
    version,
  } = eksData;

  const totalNodes = dashboard?.clusterOverview?.totalNodes || 0;
  const readyNodes = dashboard?.nodeMetrics?.nodes
    ? (() => {
        const nodes = dashboard.nodeMetrics.nodes;
        const unique = nodes.reduce((acc: any[], n: any) => {
          if (!acc.find((x: any) => x.name === n.name)) acc.push(n);
          return acc;
        }, []);
        return unique.filter((n: any) => n.ready).length;
      })()
    : totalNodes;

  const spotNodes = dashboard?.karpenter?.spotNodesCount || 0;

  const runningPods = dashboard?.clusterOverview?.runningPods ||
    (dashboard?.workloadHealth?.pods || []).filter((p: any) => p.phase === 'Running').length;
  const pendingPods = dashboard?.clusterOverview?.pendingPods ||
    (dashboard?.workloadHealth?.pods || []).filter((p: any) => p.phase === 'Pending').length;

  const namespaces = dashboard?.clusterOverview?.totalNamespaces || 0;
  const cpuPct = (dashboard?.clusterOverview?.cpuUsagePercent || 0).toFixed(1);
  const memPct = (dashboard?.clusterOverview?.memoryUsagePercent || 0).toFixed(1);
  const dailyCost = dashboard?.costMetrics?.dailyCost || 0;

  const cpuNum = parseFloat(cpuPct);
  const memNum = parseFloat(memPct);

  const getCpuBarColor = () => {
    if (cpuNum >= 80) return 'from-red-500 to-red-600';
    if (cpuNum >= 60) return 'from-amber-400 to-orange-500';
    return 'from-emerald-400 to-teal-500';
  };

  const getMemBarColor = () => {
    if (memNum >= 80) return 'from-red-500 to-red-600';
    if (memNum >= 60) return 'from-violet-400 to-purple-600';
    return 'from-blue-400 to-indigo-500';
  };

  const efficiencyLabel = costEfficiency >= 70 ? 'Optimized' : costEfficiency >= 50 ? 'Can Improve' : 'High Waste';
  const efficiencyColor = costEfficiency >= 70 ? 'bg-emerald-500' : costEfficiency >= 50 ? 'bg-amber-400' : 'bg-red-500';

  const spotSavingsPct = totalNodes > 0 && spotNodes > 0
    ? Math.round((spotNodes / totalNodes) * 70)
    : 0;

  const clusterVersion = version || 'N/A';

  return (
    <div className="space-y-4 mb-8">
      {/* ── Hero gradient strip ── */}
      <div className="relative w-full rounded-2xl overflow-hidden bg-gradient-to-r from-indigo-600 via-indigo-700 to-purple-800 shadow-xl">
        {/* Decorative glowing orbs */}
        <div className="absolute top-0 left-0 w-72 h-72 bg-indigo-400 opacity-10 rounded-full -translate-x-1/2 -translate-y-1/2 blur-3xl pointer-events-none" />
        <div className="absolute bottom-0 right-0 w-96 h-96 bg-purple-400 opacity-10 rounded-full translate-x-1/3 translate-y-1/3 blur-3xl pointer-events-none" />

        <div className="relative z-10 flex flex-col lg:flex-row items-start lg:items-center gap-6 px-8 py-6">

          {/* Left — cluster identity */}
          <div className="flex-shrink-0 flex flex-col gap-2 min-w-[160px]">
            <div className="flex items-center gap-2">
              <span className="relative flex h-3 w-3">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
                <span className="relative inline-flex rounded-full h-3 w-3 bg-emerald-400"></span>
              </span>
              <span className="text-emerald-300 text-xs font-bold tracking-widest uppercase">Live</span>
            </div>
            <div className="text-white text-xs font-semibold opacity-70 uppercase tracking-wider">EKS Cluster</div>
            <div className="inline-flex items-center gap-1.5 bg-white/15 backdrop-blur rounded-full px-3 py-1 w-fit">
              <i className="fas fa-tag text-indigo-200 text-xs"></i>
              <span className="text-white text-xs font-bold">v{clusterVersion}</span>
            </div>
            <div className="inline-flex items-center gap-1.5 bg-white/10 rounded-full px-3 py-1 w-fit mt-1">
              <i className="fas fa-heartbeat text-pink-300 text-xs"></i>
              <span className="text-white text-xs font-semibold">Score {clusterHealthScore}/100</span>
            </div>
          </div>

          {/* Divider */}
          <div className="hidden lg:block w-px h-16 bg-white/20 flex-shrink-0" />

          {/* Center — KPI pills */}
          <div className="flex-1 flex flex-wrap gap-3">
            {/* Nodes */}
            <div className="flex items-center gap-2.5 bg-white/10 hover:bg-white/20 transition-colors rounded-xl px-4 py-3 backdrop-blur-sm border border-white/10">
              <div className="w-8 h-8 rounded-lg bg-blue-400/30 flex items-center justify-center flex-shrink-0">
                <i className="fas fa-server text-blue-200 text-sm"></i>
              </div>
              <div>
                <div className="text-white font-black text-xl leading-none">
                  {readyNodes}<span className="text-white/50 text-sm font-normal">/{totalNodes}</span>
                </div>
                <div className="text-indigo-200 text-xs mt-0.5 font-medium">Nodes Ready</div>
              </div>
            </div>

            {/* Pods */}
            <div className="flex items-center gap-2.5 bg-white/10 hover:bg-white/20 transition-colors rounded-xl px-4 py-3 backdrop-blur-sm border border-white/10">
              <div className="w-8 h-8 rounded-lg bg-teal-400/30 flex items-center justify-center flex-shrink-0">
                <i className="fas fa-cubes text-teal-200 text-sm"></i>
              </div>
              <div>
                <div className="text-white font-black text-xl leading-none">
                  {runningPods}<span className="text-white/50 text-sm font-normal">/{totalPodsCount}</span>
                </div>
                <div className="text-indigo-200 text-xs mt-0.5 font-medium">Pods Running</div>
              </div>
            </div>

            {/* Namespaces */}
            <div className="flex items-center gap-2.5 bg-white/10 hover:bg-white/20 transition-colors rounded-xl px-4 py-3 backdrop-blur-sm border border-white/10">
              <div className="w-8 h-8 rounded-lg bg-purple-400/30 flex items-center justify-center flex-shrink-0">
                <i className="fas fa-layer-group text-purple-200 text-sm"></i>
              </div>
              <div>
                <div className="text-white font-black text-xl leading-none">{namespaces}</div>
                <div className="text-indigo-200 text-xs mt-0.5 font-medium">Namespaces</div>
              </div>
            </div>

            {/* CPU% */}
            <div className="flex items-center gap-2.5 bg-white/10 hover:bg-white/20 transition-colors rounded-xl px-4 py-3 backdrop-blur-sm border border-white/10">
              <div className="w-8 h-8 rounded-lg bg-orange-400/30 flex items-center justify-center flex-shrink-0">
                <i className="fas fa-microchip text-orange-200 text-sm"></i>
              </div>
              <div>
                <div className={`font-black text-xl leading-none ${cpuNum >= 80 ? 'text-red-300' : cpuNum >= 60 ? 'text-amber-300' : 'text-white'}`}>
                  {cpuPct}%
                </div>
                <div className="text-indigo-200 text-xs mt-0.5 font-medium">CPU Used</div>
              </div>
            </div>

            {/* Memory% */}
            <div className="flex items-center gap-2.5 bg-white/10 hover:bg-white/20 transition-colors rounded-xl px-4 py-3 backdrop-blur-sm border border-white/10">
              <div className="w-8 h-8 rounded-lg bg-violet-400/30 flex items-center justify-center flex-shrink-0">
                <i className="fas fa-memory text-violet-200 text-sm"></i>
              </div>
              <div>
                <div className={`font-black text-xl leading-none ${memNum >= 80 ? 'text-red-300' : memNum >= 60 ? 'text-violet-300' : 'text-white'}`}>
                  {memPct}%
                </div>
                <div className="text-indigo-200 text-xs mt-0.5 font-medium">Memory Used</div>
              </div>
            </div>

            {/* Daily Cost */}
            <div className="flex items-center gap-2.5 bg-white/10 hover:bg-white/20 transition-colors rounded-xl px-4 py-3 backdrop-blur-sm border border-white/10">
              <div className="w-8 h-8 rounded-lg bg-emerald-400/30 flex items-center justify-center flex-shrink-0">
                <i className="fas fa-dollar-sign text-emerald-200 text-sm"></i>
              </div>
              <div>
                <div className="text-white font-black text-xl leading-none">
                  ${dailyCost.toFixed(2)}
                </div>
                <div className="text-indigo-200 text-xs mt-0.5 font-medium">Daily Cost</div>
              </div>
            </div>
          </div>

          {/* Divider */}
          <div className="hidden lg:block w-px h-16 bg-white/20 flex-shrink-0" />

          {/* Right — badges */}
          <div className="flex-shrink-0 flex flex-col gap-2 min-w-[140px]">
            {spotNodes > 0 && (
              <div className="flex items-center gap-2 bg-amber-400/20 border border-amber-300/30 rounded-xl px-3 py-2">
                <span className="text-base">⚡</span>
                <div>
                  <div className="text-amber-200 text-xs font-bold">{spotSavingsPct}% Spot Savings</div>
                  <div className="text-amber-300/70 text-xs">{spotNodes} spot nodes</div>
                </div>
              </div>
            )}
            <div className={`flex items-center gap-2 rounded-xl px-3 py-2 border ${
              costEfficiency >= 70
                ? 'bg-emerald-400/20 border-emerald-300/30'
                : costEfficiency >= 50
                ? 'bg-amber-400/20 border-amber-300/30'
                : 'bg-red-400/20 border-red-300/30'
            }`}>
              <div className={`w-2 h-2 rounded-full ${efficiencyColor}`} />
              <div>
                <div className="text-white text-xs font-bold">{efficiencyLabel}</div>
                <div className="text-white/60 text-xs">Efficiency {costEfficiency}%</div>
              </div>
            </div>
            {pendingPods > 0 && (
              <div className="flex items-center gap-2 bg-red-400/20 border border-red-300/30 rounded-xl px-3 py-2">
                <i className="fas fa-exclamation-circle text-red-300 text-xs"></i>
                <div className="text-red-200 text-xs font-bold">{pendingPods} Pending Pods</div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* ── Second row: 4 action cards ── */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">

        {/* Cost Today */}
        <div className="bg-white rounded-xl border-l-4 border-l-indigo-500 border border-gray-100 shadow-sm hover:shadow-md transition-all p-5">
          <div className="flex items-center justify-between mb-1">
            <span className="text-xs font-bold text-gray-500 uppercase tracking-wider">Cost Today</span>
            <div className="w-7 h-7 rounded-lg bg-indigo-50 flex items-center justify-center">
              <i className="fas fa-dollar-sign text-indigo-600 text-xs"></i>
            </div>
          </div>
          <div className="text-4xl font-black text-gray-900 mt-2 leading-none">
            ${dailyCost.toFixed(2)}
          </div>
          <div className="mt-3 pt-3 border-t border-gray-100">
            <span className="text-xs text-gray-500">Monthly est: </span>
            <span className="text-xs font-bold text-gray-800">${(dailyCost * 30).toFixed(2)}</span>
          </div>
        </div>

        {/* Efficiency */}
        <div className={`bg-white rounded-xl border-l-4 border border-gray-100 shadow-sm hover:shadow-md transition-all p-5 ${
          costEfficiency >= 70 ? 'border-l-emerald-500' : costEfficiency >= 50 ? 'border-l-amber-400' : 'border-l-red-500'
        }`}>
          <div className="flex items-center justify-between mb-1">
            <span className="text-xs font-bold text-gray-500 uppercase tracking-wider">Efficiency</span>
            <div className={`w-7 h-7 rounded-lg flex items-center justify-center ${
              costEfficiency >= 70 ? 'bg-emerald-50' : costEfficiency >= 50 ? 'bg-amber-50' : 'bg-red-50'
            }`}>
              <i className={`fas fa-chart-line text-xs ${
                costEfficiency >= 70 ? 'text-emerald-600' : costEfficiency >= 50 ? 'text-amber-600' : 'text-red-600'
              }`}></i>
            </div>
          </div>
          <div className={`text-4xl font-black mt-2 leading-none ${
            costEfficiency >= 70 ? 'text-emerald-600' : costEfficiency >= 50 ? 'text-amber-500' : 'text-red-600'
          }`}>
            {costEfficiency}%
          </div>
          <div className="mt-3 pt-3 border-t border-gray-100">
            <span className={`inline-flex items-center gap-1 text-xs font-bold px-2 py-0.5 rounded-full ${
              costEfficiency >= 70
                ? 'bg-emerald-100 text-emerald-700'
                : costEfficiency >= 50
                ? 'bg-amber-100 text-amber-700'
                : 'bg-red-100 text-red-700'
            }`}>
              {efficiencyLabel}
            </span>
          </div>
        </div>

        {/* CPU Utilization */}
        <div className={`bg-white rounded-xl border-l-4 border border-gray-100 shadow-sm hover:shadow-md transition-all p-5 ${
          cpuNum >= 80 ? 'border-l-red-500' : cpuNum >= 60 ? 'border-l-amber-400' : 'border-l-orange-400'
        }`}>
          <div className="flex items-center justify-between mb-1">
            <span className="text-xs font-bold text-gray-500 uppercase tracking-wider">CPU Usage</span>
            <div className="w-7 h-7 rounded-lg bg-orange-50 flex items-center justify-center">
              <i className="fas fa-microchip text-orange-600 text-xs"></i>
            </div>
          </div>
          <div className={`text-4xl font-black mt-2 leading-none ${
            cpuNum >= 80 ? 'text-red-600' : cpuNum >= 60 ? 'text-amber-500' : 'text-gray-900'
          }`}>
            {cpuPct}%
          </div>
          <div className="mt-3">
            <div className="w-full h-2 bg-gray-100 rounded-full overflow-hidden">
              <div
                className={`h-full rounded-full bg-gradient-to-r transition-all duration-1000 ${getCpuBarColor()}`}
                style={{ width: `${Math.min(100, cpuNum)}%` }}
              />
            </div>
            <div className="text-xs text-gray-500 mt-1.5">
              {Math.round(dashboard?.clusterOverview?.cpuUsageMillicores || 0)}m / {Math.round(dashboard?.clusterOverview?.cpuCapacityMillicores || 0)}m
            </div>
          </div>
        </div>

        {/* Memory Utilization */}
        <div className={`bg-white rounded-xl border-l-4 border border-gray-100 shadow-sm hover:shadow-md transition-all p-5 ${
          memNum >= 80 ? 'border-l-red-500' : memNum >= 60 ? 'border-l-purple-500' : 'border-l-blue-500'
        }`}>
          <div className="flex items-center justify-between mb-1">
            <span className="text-xs font-bold text-gray-500 uppercase tracking-wider">Memory Usage</span>
            <div className="w-7 h-7 rounded-lg bg-blue-50 flex items-center justify-center">
              <i className="fas fa-memory text-blue-600 text-xs"></i>
            </div>
          </div>
          <div className={`text-4xl font-black mt-2 leading-none ${
            memNum >= 80 ? 'text-red-600' : memNum >= 60 ? 'text-purple-600' : 'text-gray-900'
          }`}>
            {memPct}%
          </div>
          <div className="mt-3">
            <div className="w-full h-2 bg-gray-100 rounded-full overflow-hidden">
              <div
                className={`h-full rounded-full bg-gradient-to-r transition-all duration-1000 ${getMemBarColor()}`}
                style={{ width: `${Math.min(100, memNum)}%` }}
              />
            </div>
            <div className="text-xs text-gray-500 mt-1.5">
              {((dashboard?.clusterOverview?.memoryUsageBytes || 0) / (1024 ** 3)).toFixed(1)} GB / {((dashboard?.clusterOverview?.memoryCapacityBytes || 0) / (1024 ** 3)).toFixed(1)} GB
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default EksSummaryCards;
