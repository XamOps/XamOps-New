/**
 * EKS Performance Tab
 *
 * Professional resource utilization monitoring
 */

import React from 'react';

interface EksPerformanceTabProps {
  eksData: any;
}

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

  // Filter for only ready/running nodes from resourceUtilization
  // Cross-reference with nodeMetrics to get the ready status
  const allUtilizationNodes = dashboard.resourceUtilization?.nodes || [];
  const nodeMetrics = dashboard.nodeMetrics?.nodes || [];

  const readyNodes = allUtilizationNodes.filter((utilNode: any) => {
    // Cross-reference with nodeMetrics to check if it's ready
    const nodeInfo = nodeMetrics.find((n: any) => n.name === utilNode.nodeName);
    return nodeInfo?.ready === true;
  });

  // Calculate stroke dasharray for circular progress
  const circumference = 2 * Math.PI * 54; // 2 * π * radius
  const cpuDasharray = `${(cpuUsagePercent / 100) * circumference} ${circumference}`;
  const memoryDasharray = `${(memoryUsagePercent / 100) * circumference} ${circumference}`;

  // Get colors based on usage percentage
  const getCpuColor = () => {
    if (cpuUsagePercent >= 80) return '#ef4444'; // red
    if (cpuUsagePercent >= 60) return '#f59e0b'; // amber
    return '#64748b'; // slate
  };

  const getMemoryColor = () => {
    if (memoryUsagePercent >= 80) return '#ef4444'; // red
    if (memoryUsagePercent >= 60) return '#f59e0b'; // amber
    return '#64748b'; // slate
  };

  const formatBytes = (bytes: number) => {
    const gb = bytes / (1024 * 1024 * 1024);
    return `${gb.toFixed(1)} GB`;
  };

  const formatCores = (millicores: number) => {
    return `${(millicores / 1000).toFixed(2)}`;
  };

  // Calculate additional metrics
  const totalCpuCores = (cpuCapacityMillicores / 1000).toFixed(1);
  const usedCpuCores = (cpuUsageMillicores / 1000).toFixed(1);
  const totalMemoryGB = (memoryCapacityBytes / (1024 * 1024 * 1024)).toFixed(1);
  const usedMemoryGB = (memoryUsageBytes / (1024 * 1024 * 1024)).toFixed(1);

  // Pod density (pods per node)
  const podDensity = totalNodes > 0 ? (totalPods / totalNodes).toFixed(1) : '0';

  // Resource efficiency score
  const efficiencyScore = ((cpuUsagePercent + memoryUsagePercent) / 2).toFixed(0);

  // Utilization trend indicator
  const isOptimal = cpuUsagePercent >= 40 && cpuUsagePercent <= 75 && memoryUsagePercent >= 40 && memoryUsagePercent <= 75;

  // Calculate average node utilization (Fix: Ensure dividing by length > 0)
  const avgNodeCpuUsage = readyNodes.length > 0
    ? (readyNodes.reduce((sum: number, node: any) => sum + (node.cpuUsagePercent || 0), 0) / readyNodes.length).toFixed(1)
    : '0';
  const avgNodeMemUsage = readyNodes.length > 0
    ? (readyNodes.reduce((sum: number, node: any) => sum + (node.memoryUsagePercent || 0), 0) / readyNodes.length).toFixed(1)
    : '0';

  // Running pods vs total capacity
  const runningPods = dashboard.workloadHealth?.pods?.filter((p: any) => p.phase === 'Running').length || 0;
  const podUtilizationPercent = totalPods > 0 ? ((runningPods / totalPods) * 100).toFixed(0) : '0';

  return (
    <div className="space-y-6">
      {/* Performance Overview */}
      <section className="bg-white rounded-xl shadow-sm p-8 border border-gray-200">
        <div className="flex items-center justify-between mb-8">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-slate-100 flex items-center justify-center">
              <i className="fas fa-tachometer-alt text-slate-700 text-lg"></i>
            </div>
            <div>
              <h3 className="text-xl font-bold text-gray-900">Cluster Performance</h3>
              <p className="text-sm text-gray-600">Real-time resource utilization metrics</p>
            </div>
          </div>

          <div className={`px-4 py-2 rounded-lg border-2 flex items-center gap-2 ${isOptimal ? 'bg-emerald-50 border-emerald-200' : 'bg-amber-50 border-amber-200'
            }`}>
            <div className={`w-2 h-2 rounded-full ${isOptimal ? 'bg-emerald-500' : 'bg-amber-500'} animate-pulse`}></div>
            <span className={`font-semibold text-sm ${isOptimal ? 'text-emerald-700' : 'text-amber-700'}`}>
              {isOptimal ? 'Optimized' : 'Needs Tuning'}
            </span>
          </div>
        </div>

        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div className="flex items-center gap-2 mb-2">
              <i className="fas fa-server text-slate-600 text-sm"></i>
              <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Active Nodes</span>
            </div>
            <div className="text-2xl font-bold text-gray-900">{readyNodes.length}</div>
            <div className="text-xs text-gray-500 mt-1">{podDensity} pods per node</div>
          </div>

          <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div className="flex items-center gap-2 mb-2">
              <i className="fas fa-boxes text-slate-600 text-sm"></i>
              <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Running Pods</span>
            </div>
            <div className="text-2xl font-bold text-gray-900">{runningPods}</div>
            <div className="text-xs text-gray-500 mt-1">{podUtilizationPercent}% of {totalPods} total</div>
          </div>

          <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div className="flex items-center gap-2 mb-2">
              <i className="fas fa-microchip text-slate-600 text-sm"></i>
              <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Total CPU</span>
            </div>
            <div className="text-2xl font-bold text-gray-900">{totalCpuCores}</div>
            <div className="text-xs text-gray-500 mt-1">{usedCpuCores} cores in use</div>
          </div>

          <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div className="flex items-center gap-2 mb-2">
              <i className="fas fa-memory text-slate-600 text-sm"></i>
              <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Total Memory</span>
            </div>
            <div className="text-2xl font-bold text-gray-900">{totalMemoryGB}</div>
            <div className="text-xs text-gray-500 mt-1">{usedMemoryGB} GB in use</div>
          </div>
        </div>
      </section>

      {/* Resource Utilization Gauges */}
      <section className="bg-white rounded-xl shadow-sm p-8 border border-gray-200">
        <div className="flex items-center gap-3 mb-8">
          <div className="w-10 h-10 rounded-lg bg-slate-100 flex items-center justify-center">
            <i className="fas fa-chart-pie text-slate-700 text-lg"></i>
          </div>
          <h3 className="text-xl font-bold text-gray-900">Resource Utilization</h3>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-12">
          {/* CPU Gauge */}
          <div className="flex flex-col items-center">
            <div className="relative w-48 h-48 mb-6">
              <svg className="transform -rotate-90 w-full h-full" viewBox="0 0 120 120">
                <circle
                  cx="60"
                  cy="60"
                  r="54"
                  fill="none"
                  stroke="#e5e7eb"
                  strokeWidth="8"
                ></circle>
                <circle
                  cx="60"
                  cy="60"
                  r="54"
                  fill="none"
                  stroke={getCpuColor()}
                  strokeWidth="8"
                  strokeLinecap="round"
                  strokeDasharray={cpuDasharray}
                  className="transition-all duration-1000"
                ></circle>
              </svg>
              <div className="absolute inset-0 flex flex-col items-center justify-center">
                <div className="text-4xl font-bold" style={{ color: getCpuColor() }}>
                  {Math.round(cpuUsageMillicores)}
                </div>
                <div className="text-base text-gray-500 font-medium">m</div>
                <div className="text-xs text-gray-400 mt-1">Millicores</div>
              </div>
            </div>

            <div className="w-full">
              <h4 className="text-base font-semibold text-gray-900 mb-4 text-center">CPU Utilization</h4>
              <div className="space-y-3 bg-gray-50 rounded-lg p-4 border border-gray-200">
                <div className="flex justify-between items-center">
                  <span className="text-sm text-gray-600">Used</span>
                  <span className="text-sm font-semibold text-gray-900">
                    {Math.round(cpuUsageMillicores)}m
                  </span>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-sm text-gray-600">Capacity</span>
                  <span className="text-sm font-semibold text-gray-900">
                    {Math.round(cpuCapacityMillicores)}m
                  </span>
                </div>
                <div className="flex justify-between items-center pt-3 border-t border-gray-200">
                  <span className="text-sm text-gray-600">Avg Node Usage</span>
                  <span className="text-sm font-semibold text-gray-900">{avgNodeCpuUsage}%</span>
                </div>
              </div>
            </div>
          </div>

          {/* Memory Gauge */}
          <div className="flex flex-col items-center">
            <div className="relative w-48 h-48 mb-6">
              <svg className="transform -rotate-90 w-full h-full" viewBox="0 0 120 120">
                <circle
                  cx="60"
                  cy="60"
                  r="54"
                  fill="none"
                  stroke="#e5e7eb"
                  strokeWidth="8"
                ></circle>
                <circle
                  cx="60"
                  cy="60"
                  r="54"
                  fill="none"
                  stroke={getMemoryColor()}
                  strokeWidth="8"
                  strokeLinecap="round"
                  strokeDasharray={memoryDasharray}
                  className="transition-all duration-1000"
                ></circle>
              </svg>
              <div className="absolute inset-0 flex flex-col items-center justify-center">
                <div className="text-4xl font-bold" style={{ color: getMemoryColor() }}>
                  {memoryUsagePercent.toFixed(0)}
                </div>
                <div className="text-base text-gray-500 font-medium">%</div>
                <div className="text-xs text-gray-400 mt-1">Memory</div>
              </div>
            </div>

            <div className="w-full">
              <h4 className="text-base font-semibold text-gray-900 mb-4 text-center">Memory Utilization</h4>
              <div className="space-y-3 bg-gray-50 rounded-lg p-4 border border-gray-200">
                <div className="flex justify-between items-center">
                  <span className="text-sm text-gray-600">Used</span>
                  <span className="text-sm font-semibold text-gray-900">
                    {formatBytes(memoryUsageBytes)}
                  </span>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-sm text-gray-600">Capacity</span>
                  <span className="text-sm font-semibold text-gray-900">
                    {formatBytes(memoryCapacityBytes)}
                  </span>
                </div>
                <div className="flex justify-between items-center pt-3 border-t border-gray-200">
                  <span className="text-sm text-gray-600">Avg Node Usage</span>
                  <span className="text-sm font-semibold text-gray-900">{avgNodeMemUsage}%</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Node Resource Breakdown */}
      <section className="bg-white rounded-xl shadow-sm p-8 border border-gray-200">
        <div className="flex items-center gap-3 mb-6">
          <div className="w-10 h-10 rounded-lg bg-blue-50 flex items-center justify-center">
            <i className="fas fa-server text-blue-600 text-lg"></i>
          </div>
          <h3 className="text-xl font-bold text-gray-900">
            Node Resource Breakdown
            <span className="ml-2 text-sm font-normal text-gray-500">
              ({readyNodes.length} active nodes)
            </span>
          </h3>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead>
              <tr className="border-b-2 border-gray-200">
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  <i className="fas fa-server mr-2"></i>Node Name
                </th>
                <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  <i className="fas fa-microchip mr-2"></i>CPU Usage
                </th>
                <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  <i className="fas fa-memory mr-2"></i>Memory Usage
                </th>
                <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  <i className="fas fa-chart-bar mr-2"></i>CPU Requests
                </th>
                <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  <i className="fas fa-chart-bar mr-2"></i>Memory Requests
                </th>
              </tr>
            </thead>
            <tbody>
              {readyNodes.map((node: any, index: number) => {
                // Calculate request percentages
                // Use pre-calculated percentages from DTO if available, otherwise fallback to calculation
                const cpuReqPercent = node.cpuRequestsPercent !== undefined ? node.cpuRequestsPercent :
                  (node.cpuCapacity ? (node.cpuRequests / (node.cpuCapacity * 1000)) * 100 : 0);
                const memReqPercent = node.memoryRequestsPercent !== undefined ? node.memoryRequestsPercent :
                  (node.memoryCapacity ? (node.memoryRequests / (node.memoryCapacity * 1024)) * 100 : 0);

                return (
                  <tr key={index} className="border-t border-gray-100 hover:bg-gray-50 transition-colors">
                    <td className="px-4 py-4 text-sm font-mono font-semibold text-gray-900">
                      {node.nodeName}
                    </td>
                    <td className="px-4 py-4 text-sm text-center">
                      <div className="flex flex-col items-center">
                        <span className="text-xs text-gray-500 font-medium">Usage</span>
                        <span className="font-bold text-gray-900">{Math.round(node.cpuUsageMillicores || 0)}m</span>
                        <span className="text-xs text-gray-400">({(node.cpuUsagePercent || 0).toFixed(1)}%)</span>
                      </div>
                    </td>
                    <td className="px-4 py-4 text-sm text-center">
                      <div className="flex flex-col items-center">
                        <span className="text-xs text-gray-500 font-medium">Usage</span>
                        <span className="font-bold text-gray-900">
                          {node.memoryCapacity ? Math.round((node.memoryUsagePercent || 0) * node.memoryCapacity * 1024 / 100) : 0} MB
                        </span>
                        <span className="text-xs text-gray-400">({(node.memoryUsagePercent || 0).toFixed(1)}%)</span>
                        {/* Note: Back-calc from percent since we don't have absolute usage bytes in node metrics easily available w/o extra fetch, but wait we DO have memory usage percent. Let's just use what we have.*/}
                      </div>
                    </td>
                    <td className="px-4 py-4 text-center">
                      <div className="flex flex-col items-center">
                        <span className="text-xs text-gray-500 font-medium">Requests</span>
                        <span className="font-bold text-gray-900">{Math.round(node.cpuRequests || 0)}m</span>
                        <span className="text-xs text-gray-400">({cpuReqPercent.toFixed(1)}%)</span>
                      </div>
                    </td>
                    <td className="px-4 py-4 text-center">
                      <div className="flex flex-col items-center">
                        <span className="text-xs text-gray-500 font-medium">Requests</span>
                        <span className="font-bold text-gray-900">{Math.round(node.memoryRequests || 0)} MB</span>
                        <span className="text-xs text-gray-400">({memReqPercent.toFixed(1)}%)</span>
                      </div>
                    </td>
                  </tr>
                )
              })}
              {readyNodes.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-12 text-center">
                    <div className="flex flex-col items-center">
                      <div className="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mb-3">
                        <i className="fas fa-server text-gray-300 text-2xl"></i>
                      </div>
                      <p className="text-sm text-gray-500 font-medium">No active node metrics available</p>
                    </div>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      {/* Performance Insights */}
      <section className="bg-white rounded-xl shadow-sm p-8 border border-gray-200">
        <div className="flex items-center gap-3 mb-6">
          <div className="w-10 h-10 rounded-lg bg-emerald-50 flex items-center justify-center">
            <i className="fas fa-chart-line text-emerald-600 text-lg"></i>
          </div>
          <h3 className="text-xl font-bold text-gray-900">Performance Insights</h3>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          {/* Resource Efficiency */}
          <div className="bg-gray-50 rounded-lg p-6 border border-gray-200 hover:border-gray-300 hover:shadow-sm transition-all">
            <div className="flex items-center justify-between mb-4">
              <div className="w-12 h-12 bg-slate-100 rounded-lg flex items-center justify-center">
                <i className="fas fa-tachometer-alt text-slate-700 text-xl"></i>
              </div>
              <span className={`text-2xl font-bold ${Number(efficiencyScore) >= 60 ? 'text-emerald-600' :
                Number(efficiencyScore) >= 40 ? 'text-amber-600' : 'text-red-600'
                }`}>
                {efficiencyScore}%
              </span>
            </div>
            <h4 className="font-semibold text-gray-900 text-base mb-2">Resource Efficiency</h4>
            <p className="text-sm text-gray-600 mb-3">
              Average utilization across CPU and memory
            </p>
            <div className="h-2 bg-gray-200 rounded-full overflow-hidden">
              <div
                className={`h-full rounded-full transition-all duration-500 ${Number(efficiencyScore) >= 60 ? 'bg-emerald-500' :
                  Number(efficiencyScore) >= 40 ? 'bg-amber-500' : 'bg-red-500'
                  }`}
                style={{ width: `${efficiencyScore}%` }}
              ></div>
            </div>
          </div>

          {/* Pod Density */}
          <div className="bg-gray-50 rounded-lg p-6 border border-gray-200 hover:border-gray-300 hover:shadow-sm transition-all">
            <div className="flex items-center justify-between mb-4">
              <div className="w-12 h-12 bg-indigo-50 rounded-lg flex items-center justify-center">
                <i className="fas fa-cubes text-indigo-600 text-xl"></i>
              </div>
              <span className="text-2xl font-bold text-indigo-600">
                {podDensity}
              </span>
            </div>
            <h4 className="font-semibold text-gray-900 text-base mb-2">Pod Density</h4>
            <p className="text-sm text-gray-600 mb-3">
              Average pods per node for balanced distribution
            </p>
            <div className="text-xs text-gray-500">
              {totalPods} pods across {readyNodes.length} nodes
            </div>
          </div>

          {/* Allocation Status */}
          <div className="bg-gray-50 rounded-lg p-6 border border-gray-200 hover:border-gray-300 hover:shadow-sm transition-all">
            <div className="flex items-center justify-between mb-4">
              <div className="w-12 h-12 bg-blue-50 rounded-lg flex items-center justify-center">
                <i className="fas fa-chart-pie text-blue-600 text-xl"></i>
              </div>
              <span className={`text-2xl font-bold ${isOptimal ? 'text-emerald-600' : 'text-amber-600'}`}>
                {isOptimal ? '✓' : '!'}
              </span>
            </div>
            <h4 className="font-semibold text-gray-900 text-base mb-2">Allocation Status</h4>
            <p className="text-sm text-gray-600 mb-3">
              {isOptimal
                ? 'Resources optimally allocated (40-75% range)'
                : 'Consider scaling resources for efficiency'
              }
            </p>
            <div className="flex gap-2">
              <div className="flex-1 text-center p-2 bg-white rounded-lg border border-gray-200">
                <div className="text-xs text-gray-600 uppercase font-semibold">CPU</div>
                <div className="text-sm font-bold text-gray-900">{cpuUsagePercent.toFixed(0)}%</div>
              </div>
              <div className="flex-1 text-center p-2 bg-white rounded-lg border border-gray-200">
                <div className="text-xs text-gray-600 uppercase font-semibold">Memory</div>
                <div className="text-sm font-bold text-gray-900">{memoryUsagePercent.toFixed(0)}%</div>
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>
  );
};

export default EksPerformanceTab;
