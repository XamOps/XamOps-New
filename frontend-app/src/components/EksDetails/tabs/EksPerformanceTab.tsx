/**
 * EKS Performance Tab — Cast.ai Premium Level
 *
 * Dark gradient hero, SVG donut gauges, node performance cards,
 * and actionable performance insights.
 */

import React from 'react';

interface EksPerformanceTabProps {
  eksData: any;
}

const DonutGauge: React.FC<{
  pct: number;
  color: string;
  trackColor?: string;
  size?: number;
  strokeWidth?: number;
  children: React.ReactNode;
}> = ({ pct, color, trackColor = '#e5e7eb', size = 140, strokeWidth = 10, children }) => {
  const r = (size - strokeWidth * 2) / 2;
  const circ = 2 * Math.PI * r;
  const filled = Math.max(0, Math.min(100, pct)) / 100 * circ;
  return (
    <div className="relative flex items-center justify-center" style={{ width: size, height: size }}>
      <svg width={size} height={size} className="-rotate-90">
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={trackColor} strokeWidth={strokeWidth} />
        <circle
          cx={size / 2} cy={size / 2} r={r}
          fill="none" stroke={color} strokeWidth={strokeWidth}
          strokeLinecap="round"
          strokeDasharray={`${filled} ${circ}`}
          className="transition-all duration-1000"
        />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        {children}
      </div>
    </div>
  );
};

const EksPerformanceTab: React.FC<EksPerformanceTabProps> = ({ eksData }) => {
  const { dashboard } = eksData;
  if (!dashboard) return null;

  const cpuUsagePercent = dashboard.clusterOverview?.cpuUsagePercent || 0;
  const memoryUsagePercent = dashboard.clusterOverview?.memoryUsagePercent || 0;
  const cpuUsageMillicores = dashboard.clusterOverview?.cpuUsageMillicores || 0;
  const cpuCapacityMillicores = dashboard.clusterOverview?.cpuCapacityMillicores || 0;
  const memoryUsageBytes = dashboard.clusterOverview?.memoryUsageBytes || 0;
  const memoryCapacityBytes = dashboard.clusterOverview?.memoryCapacityBytes || 0;
  const totalNodes = dashboard.clusterOverview?.totalNodes || 0;
  const totalPods = dashboard.workloadHealth?.pods?.length || 0;

  const allUtilizationNodes = dashboard.resourceUtilization?.nodes || [];
  const nodeMetrics = dashboard.nodeMetrics?.nodes || [];

  const readyNodes = allUtilizationNodes.filter((utilNode: any) => {
    const nodeInfo = nodeMetrics.find((n: any) => n.name === utilNode.nodeName);
    return nodeInfo?.ready === true;
  });

  const getCpuColor = () => {
    if (cpuUsagePercent >= 80) return '#ef4444';
    if (cpuUsagePercent >= 60) return '#f59e0b';
    return '#10b981';
  };

  const getMemoryColor = () => {
    if (memoryUsagePercent >= 80) return '#ef4444';
    if (memoryUsagePercent >= 60) return '#f59e0b';
    return '#6366f1';
  };

  const getUsageColor = (pct: number) => {
    if (pct >= 80) return { text: 'text-red-600', bg: 'bg-red-500', light: 'bg-red-50', badge: 'bg-red-100 text-red-700' };
    if (pct >= 60) return { text: 'text-amber-600', bg: 'bg-amber-500', light: 'bg-amber-50', badge: 'bg-amber-100 text-amber-700' };
    return { text: 'text-emerald-600', bg: 'bg-emerald-500', light: 'bg-emerald-50', badge: 'bg-emerald-100 text-emerald-700' };
  };

  const formatBytes = (bytes: number) => `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;
  const totalCpuCores = (cpuCapacityMillicores / 1000).toFixed(1);
  const usedCpuCores = (cpuUsageMillicores / 1000).toFixed(1);
  const totalMemoryGB = (memoryCapacityBytes / (1024 * 1024 * 1024)).toFixed(1);
  const usedMemoryGB = (memoryUsageBytes / (1024 * 1024 * 1024)).toFixed(1);
  const podDensity = totalNodes > 0 ? (totalPods / totalNodes).toFixed(1) : '0';
  const efficiencyScore = ((cpuUsagePercent + memoryUsagePercent) / 2).toFixed(0);
  const isOptimal = cpuUsagePercent >= 40 && cpuUsagePercent <= 75 && memoryUsagePercent >= 40 && memoryUsagePercent <= 75;
  const runningPods = dashboard.workloadHealth?.pods?.filter((p: any) => p.phase === 'Running').length || 0;
  const avgNodeCpuUsage = readyNodes.length > 0
    ? (readyNodes.reduce((s: number, n: any) => s + (n.cpuUsagePercent || 0), 0) / readyNodes.length).toFixed(1) : '0';
  const avgNodeMemUsage = readyNodes.length > 0
    ? (readyNodes.reduce((s: number, n: any) => s + (n.memoryUsagePercent || 0), 0) / readyNodes.length).toFixed(1) : '0';

  const cpuColors = getUsageColor(cpuUsagePercent);
  const memColors = getUsageColor(memoryUsagePercent);

  return (
    <div className="space-y-6">

      {/* ═══ HERO: Performance Command Center ═══ */}
      <div className="bg-gradient-to-br from-slate-800 via-slate-700 to-slate-900 rounded-2xl p-8 text-white shadow-xl relative overflow-hidden">
        <div className="absolute -top-12 -right-12 w-48 h-48 bg-white/5 rounded-full"></div>
        <div className="absolute -bottom-8 right-24 w-32 h-32 bg-white/5 rounded-full"></div>

        <div className="relative">
          <div className="flex items-center justify-between mb-8">
            <div>
              <p className="text-slate-400 text-xs font-bold uppercase tracking-widest mb-1">Performance Monitor</p>
              <h2 className="text-2xl font-black text-white">Cluster Resource Utilization</h2>
            </div>
            <div className={`flex items-center gap-2 px-4 py-2 rounded-full border ${isOptimal ? 'bg-emerald-500/20 border-emerald-400/30 text-emerald-300' : 'bg-amber-500/20 border-amber-400/30 text-amber-300'}`}>
              <div className={`w-2 h-2 rounded-full animate-pulse ${isOptimal ? 'bg-emerald-400' : 'bg-amber-400'}`}></div>
              <span className="text-sm font-bold">{isOptimal ? 'Optimized' : 'Needs Tuning'}</span>
            </div>
          </div>

          {/* KPI pills row */}
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            <div className="bg-white/10 backdrop-blur rounded-xl p-4 border border-white/10">
              <p className="text-slate-400 text-xs font-bold uppercase tracking-widest mb-2">Active Nodes</p>
              <p className="text-4xl font-black text-white">{readyNodes.length}</p>
              <p className="text-slate-400 text-xs mt-1">{podDensity} pods/node avg</p>
            </div>
            <div className="bg-white/10 backdrop-blur rounded-xl p-4 border border-white/10">
              <p className="text-slate-400 text-xs font-bold uppercase tracking-widest mb-2">Running Pods</p>
              <p className="text-4xl font-black text-white">{runningPods}</p>
              <p className="text-slate-400 text-xs mt-1">of {totalPods} total</p>
            </div>
            <div className={`rounded-xl p-4 border ${cpuUsagePercent >= 80 ? 'bg-red-500/20 border-red-400/30' : cpuUsagePercent >= 60 ? 'bg-amber-500/20 border-amber-400/30' : 'bg-emerald-500/20 border-emerald-400/30'}`}>
              <p className="text-slate-400 text-xs font-bold uppercase tracking-widest mb-2">CPU</p>
              <p className={`text-4xl font-black ${cpuUsagePercent >= 80 ? 'text-red-300' : cpuUsagePercent >= 60 ? 'text-amber-300' : 'text-emerald-300'}`}>{cpuUsagePercent.toFixed(0)}%</p>
              <p className="text-slate-400 text-xs mt-1">{usedCpuCores} / {totalCpuCores} cores</p>
            </div>
            <div className={`rounded-xl p-4 border ${memoryUsagePercent >= 80 ? 'bg-red-500/20 border-red-400/30' : memoryUsagePercent >= 60 ? 'bg-amber-500/20 border-amber-400/30' : 'bg-indigo-500/20 border-indigo-400/30'}`}>
              <p className="text-slate-400 text-xs font-bold uppercase tracking-widest mb-2">Memory</p>
              <p className={`text-4xl font-black ${memoryUsagePercent >= 80 ? 'text-red-300' : memoryUsagePercent >= 60 ? 'text-amber-300' : 'text-indigo-300'}`}>{memoryUsagePercent.toFixed(0)}%</p>
              <p className="text-slate-400 text-xs mt-1">{usedMemoryGB} / {totalMemoryGB} GB</p>
            </div>
          </div>
        </div>
      </div>

      {/* ═══ RESOURCE UTILIZATION GAUGES ═══ */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        {/* CPU Gauge Card */}
        <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-8">
          <div className="flex items-center gap-3 mb-6">
            <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${cpuColors.light}`}>
              <i className={`fas fa-microchip ${cpuColors.text}`}></i>
            </div>
            <div>
              <h3 className="font-bold text-gray-900">CPU Utilization</h3>
              <p className="text-xs text-gray-500">Cluster-wide compute usage</p>
            </div>
            <div className={`ml-auto text-xs font-bold px-3 py-1 rounded-full ${cpuColors.badge}`}>
              {cpuUsagePercent >= 80 ? 'High Load' : cpuUsagePercent >= 60 ? 'Moderate' : 'Healthy'}
            </div>
          </div>

          <div className="flex items-center gap-8">
            <DonutGauge pct={cpuUsagePercent} color={getCpuColor()} size={160} strokeWidth={14}>
              <span className="text-3xl font-black" style={{ color: getCpuColor() }}>{cpuUsagePercent.toFixed(0)}</span>
              <span className="text-xs text-gray-500 font-semibold">%</span>
            </DonutGauge>

            <div className="flex-1 space-y-4">
              <div>
                <div className="flex justify-between text-xs font-semibold text-gray-600 mb-1.5">
                  <span>Used</span><span className="text-gray-900">{Math.round(cpuUsageMillicores)}m / {Math.round(cpuCapacityMillicores)}m</span>
                </div>
                <div className="h-2 bg-gray-100 rounded-full overflow-hidden">
                  <div className={`h-full ${cpuColors.bg} rounded-full transition-all duration-700`} style={{ width: `${cpuUsagePercent}%` }}></div>
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div className="bg-gray-50 rounded-xl p-3 border border-gray-100">
                  <p className="text-xs text-gray-500 mb-1">Cores Used</p>
                  <p className="text-xl font-black text-gray-900">{usedCpuCores}</p>
                  <p className="text-xs text-gray-400">of {totalCpuCores} total</p>
                </div>
                <div className="bg-gray-50 rounded-xl p-3 border border-gray-100">
                  <p className="text-xs text-gray-500 mb-1">Avg / Node</p>
                  <p className="text-xl font-black text-gray-900">{avgNodeCpuUsage}%</p>
                  <p className="text-xs text-gray-400">node average</p>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Memory Gauge Card */}
        <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-8">
          <div className="flex items-center gap-3 mb-6">
            <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${memColors.light}`}>
              <i className={`fas fa-memory ${memColors.text}`}></i>
            </div>
            <div>
              <h3 className="font-bold text-gray-900">Memory Utilization</h3>
              <p className="text-xs text-gray-500">Cluster-wide RAM usage</p>
            </div>
            <div className={`ml-auto text-xs font-bold px-3 py-1 rounded-full ${memColors.badge}`}>
              {memoryUsagePercent >= 80 ? 'High Load' : memoryUsagePercent >= 60 ? 'Moderate' : 'Healthy'}
            </div>
          </div>

          <div className="flex items-center gap-8">
            <DonutGauge pct={memoryUsagePercent} color={getMemoryColor()} size={160} strokeWidth={14}>
              <span className="text-3xl font-black" style={{ color: getMemoryColor() }}>{memoryUsagePercent.toFixed(0)}</span>
              <span className="text-xs text-gray-500 font-semibold">%</span>
            </DonutGauge>

            <div className="flex-1 space-y-4">
              <div>
                <div className="flex justify-between text-xs font-semibold text-gray-600 mb-1.5">
                  <span>Used</span><span className="text-gray-900">{formatBytes(memoryUsageBytes)} / {formatBytes(memoryCapacityBytes)}</span>
                </div>
                <div className="h-2 bg-gray-100 rounded-full overflow-hidden">
                  <div className={`h-full ${memColors.bg} rounded-full transition-all duration-700`} style={{ width: `${memoryUsagePercent}%` }}></div>
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div className="bg-gray-50 rounded-xl p-3 border border-gray-100">
                  <p className="text-xs text-gray-500 mb-1">Used</p>
                  <p className="text-xl font-black text-gray-900">{usedMemoryGB}</p>
                  <p className="text-xs text-gray-400">GB consumed</p>
                </div>
                <div className="bg-gray-50 rounded-xl p-3 border border-gray-100">
                  <p className="text-xs text-gray-500 mb-1">Avg / Node</p>
                  <p className="text-xl font-black text-gray-900">{avgNodeMemUsage}%</p>
                  <p className="text-xs text-gray-400">node average</p>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* ═══ NODE PERFORMANCE CARDS ═══ */}
      <div className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden">
        <div className="px-6 py-5 border-b border-gray-100 flex items-center gap-3">
          <div className="w-10 h-10 bg-blue-50 rounded-xl flex items-center justify-center">
            <i className="fas fa-server text-blue-600"></i>
          </div>
          <div>
            <h3 className="text-base font-bold text-gray-900">Node Resource Breakdown</h3>
            <p className="text-xs text-gray-500">{readyNodes.length} active nodes · CPU & memory utilization</p>
          </div>
        </div>

        <div className="divide-y divide-gray-50">
          {readyNodes.map((node: any, index: number) => {
            const cpuReqPercent = node.cpuRequestsPercent !== undefined ? node.cpuRequestsPercent
              : (node.cpuCapacity ? (node.cpuRequests / (node.cpuCapacity * 1000)) * 100 : 0);
            const memReqPercent = node.memoryRequestsPercent !== undefined ? node.memoryRequestsPercent
              : (node.memoryCapacity ? (node.memoryRequests / (node.memoryCapacity * 1024)) * 100 : 0);
            const cpu = node.cpuUsagePercent || 0;
            const mem = node.memoryUsagePercent || 0;
            const cpuC = getUsageColor(cpu);
            const memC = getUsageColor(mem);
            const cpuMillicores = Math.round(node.cpuUsageMillicores || 0);

            return (
              <div key={index} className="px-6 py-5 hover:bg-gray-50 transition-colors">
                <div className="flex items-center justify-between mb-4">
                  <div className="flex items-center gap-3">
                    <div className="w-9 h-9 bg-blue-50 rounded-lg flex items-center justify-center">
                      <i className="fas fa-server text-blue-500 text-sm"></i>
                    </div>
                    <div>
                      <p className="font-mono text-sm font-bold text-gray-900">{node.nodeName}</p>
                    </div>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className={`text-xs px-2.5 py-1 rounded-full font-bold ${cpuC.badge}`}>CPU {cpu.toFixed(0)}%</span>
                    <span className={`text-xs px-2.5 py-1 rounded-full font-bold ${memC.badge}`}>MEM {mem.toFixed(0)}%</span>
                  </div>
                </div>

                <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                  {/* CPU Usage bar */}
                  <div>
                    <div className="flex justify-between text-xs mb-1.5">
                      <span className="text-gray-500 flex items-center gap-1">
                        <i className="fas fa-microchip text-orange-400 text-[10px]"></i> CPU Usage
                      </span>
                      <span className={`font-black ${cpuC.text}`}>{cpu.toFixed(1)}%</span>
                    </div>
                    <div className="h-2 bg-gray-100 rounded-full overflow-hidden">
                      <div className={`h-full ${cpuC.bg} rounded-full transition-all`} style={{ width: `${Math.min(100, cpu)}%` }}></div>
                    </div>
                    {cpuMillicores > 0 && <p className="text-[10px] text-gray-400 mt-1">{cpuMillicores}m</p>}
                  </div>

                  {/* Memory Usage bar */}
                  <div>
                    <div className="flex justify-between text-xs mb-1.5">
                      <span className="text-gray-500 flex items-center gap-1">
                        <i className="fas fa-memory text-purple-400 text-[10px]"></i> Mem Usage
                      </span>
                      <span className={`font-black ${memC.text}`}>{mem.toFixed(1)}%</span>
                    </div>
                    <div className="h-2 bg-gray-100 rounded-full overflow-hidden">
                      <div className={`h-full bg-gradient-to-r ${mem >= 80 ? 'from-red-400 to-red-500' : mem >= 60 ? 'from-amber-400 to-amber-500' : 'from-indigo-400 to-indigo-500'} rounded-full transition-all`} style={{ width: `${Math.min(100, mem)}%` }}></div>
                    </div>
                  </div>

                  {/* CPU Requests bar */}
                  <div>
                    <div className="flex justify-between text-xs mb-1.5">
                      <span className="text-gray-500 flex items-center gap-1">
                        <i className="fas fa-chart-line text-blue-400 text-[10px]"></i> CPU Req
                      </span>
                      <span className="font-black text-blue-600">{cpuReqPercent.toFixed(1)}%</span>
                    </div>
                    <div className="h-2 bg-gray-100 rounded-full overflow-hidden">
                      <div className="h-full bg-gradient-to-r from-blue-400 to-blue-500 rounded-full transition-all" style={{ width: `${Math.min(100, cpuReqPercent)}%` }}></div>
                    </div>
                    {node.cpuRequests > 0 && <p className="text-[10px] text-gray-400 mt-1">{Math.round(node.cpuRequests)}m</p>}
                  </div>

                  {/* Memory Requests bar */}
                  <div>
                    <div className="flex justify-between text-xs mb-1.5">
                      <span className="text-gray-500 flex items-center gap-1">
                        <i className="fas fa-chart-line text-indigo-400 text-[10px]"></i> Mem Req
                      </span>
                      <span className="font-black text-indigo-600">{memReqPercent.toFixed(1)}%</span>
                    </div>
                    <div className="h-2 bg-gray-100 rounded-full overflow-hidden">
                      <div className="h-full bg-gradient-to-r from-indigo-400 to-indigo-500 rounded-full transition-all" style={{ width: `${Math.min(100, memReqPercent)}%` }}></div>
                    </div>
                    {node.memoryRequests > 0 && <p className="text-[10px] text-gray-400 mt-1">{Math.round(node.memoryRequests)} MB</p>}
                  </div>
                </div>
              </div>
            );
          })}

          {readyNodes.length === 0 && (
            <div className="px-6 py-16 text-center">
              <i className="fas fa-server text-gray-200 text-4xl mb-3"></i>
              <p className="text-gray-500 text-sm font-medium">No active node metrics available</p>
            </div>
          )}
        </div>
      </div>

      {/* ═══ PERFORMANCE INSIGHTS ═══ */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        {/* Efficiency Score */}
        <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-6 flex flex-col items-center text-center">
          <div className="mb-4">
            <DonutGauge
              pct={Number(efficiencyScore)}
              color={Number(efficiencyScore) >= 60 ? '#10b981' : Number(efficiencyScore) >= 40 ? '#f59e0b' : '#ef4444'}
              size={120}
              strokeWidth={10}
            >
              <span className={`text-2xl font-black ${Number(efficiencyScore) >= 60 ? 'text-emerald-600' : Number(efficiencyScore) >= 40 ? 'text-amber-600' : 'text-red-600'}`}>
                {efficiencyScore}%
              </span>
            </DonutGauge>
          </div>
          <h4 className="font-bold text-gray-900 mb-1">Resource Efficiency</h4>
          <p className="text-xs text-gray-500">Average CPU + memory utilization</p>
          <div className={`mt-3 text-xs font-bold px-3 py-1 rounded-full ${Number(efficiencyScore) >= 60 ? 'bg-emerald-100 text-emerald-700' : Number(efficiencyScore) >= 40 ? 'bg-amber-100 text-amber-700' : 'bg-red-100 text-red-700'}`}>
            {Number(efficiencyScore) >= 60 ? 'Good utilization' : Number(efficiencyScore) >= 40 ? 'Acceptable' : 'Under-utilized'}
          </div>
        </div>

        {/* Pod Density */}
        <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-6 flex flex-col items-center text-center">
          <div className="w-24 h-24 bg-indigo-50 rounded-2xl flex items-center justify-center mb-4 mx-auto">
            <div>
              <p className="text-4xl font-black text-indigo-600">{podDensity}</p>
              <p className="text-xs text-indigo-400 font-semibold">pods/node</p>
            </div>
          </div>
          <h4 className="font-bold text-gray-900 mb-1">Pod Density</h4>
          <p className="text-xs text-gray-500 mb-3">Workload distribution balance</p>
          <div className="grid grid-cols-2 gap-2 w-full">
            <div className="bg-gray-50 rounded-xl p-3 border border-gray-100">
              <p className="text-xs text-gray-500">Total Pods</p>
              <p className="text-lg font-black text-gray-900">{totalPods}</p>
            </div>
            <div className="bg-gray-50 rounded-xl p-3 border border-gray-100">
              <p className="text-xs text-gray-500">Running</p>
              <p className="text-lg font-black text-emerald-600">{runningPods}</p>
            </div>
          </div>
        </div>

        {/* Allocation Status */}
        <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-6 flex flex-col items-center text-center">
          <div className={`w-24 h-24 rounded-2xl flex items-center justify-center mb-4 mx-auto ${isOptimal ? 'bg-emerald-50' : 'bg-amber-50'}`}>
            <i className={`fas ${isOptimal ? 'fa-check-circle text-emerald-500' : 'fa-exclamation-circle text-amber-500'} text-5xl`}></i>
          </div>
          <h4 className="font-bold text-gray-900 mb-1">Allocation Status</h4>
          <p className="text-xs text-gray-500 mb-3">{isOptimal ? 'Resources in optimal 40–75% band' : 'Resources outside optimal range'}</p>
          <div className="grid grid-cols-2 gap-2 w-full">
            <div className={`rounded-xl p-3 border ${cpuColors.light} border-gray-100`}>
              <p className="text-xs text-gray-500 uppercase font-bold">CPU</p>
              <p className={`text-lg font-black ${cpuColors.text}`}>{cpuUsagePercent.toFixed(0)}%</p>
            </div>
            <div className={`rounded-xl p-3 border ${memColors.light} border-gray-100`}>
              <p className="text-xs text-gray-500 uppercase font-bold">Memory</p>
              <p className={`text-lg font-black ${memColors.text}`}>{memoryUsagePercent.toFixed(0)}%</p>
            </div>
          </div>
        </div>
      </div>

    </div>
  );
};

export default EksPerformanceTab;
