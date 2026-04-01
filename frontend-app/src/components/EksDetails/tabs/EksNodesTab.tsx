/**
 * EKS Nodes Tab
 *
 * Comprehensive node health and resource monitoring with modern visualizations
 */

import React, { useState, useMemo } from 'react';

const EksNodesTab: React.FC<{ eksData: any }> = ({ eksData }) => {
  const { dashboard, sortedNodesByPressure, getNodeConditionCount } = eksData;
  const nodes = dashboard?.nodeMetrics?.nodes || [];

  // State for filtering and pagination
  const [filterStatus, setFilterStatus] = useState<'all' | 'running' | 'notReady'>('running');
  const [currentPage, setCurrentPage] = useState(1);
  const NODES_PER_PAGE = 5;

  // Filter and sort nodes
  const filteredNodes = useMemo(() => {
    // Stage 1: Get base nodes (All nodes, no capacity filter)
    let baseNodes = [...sortedNodesByPressure];

    // Stage 2: Filter by Status
    let result = [...baseNodes];
    if (filterStatus === 'running') {
      result = baseNodes.filter((node: any) => {
        const fullNode = nodes.find((n: any) => n.name === node.nodeName);
        return fullNode?.ready === true;
      });
    } else if (filterStatus === 'notReady') {
      result = baseNodes.filter((node: any) => {
        const fullNode = nodes.find((n: any) => n.name === node.nodeName);
        return fullNode?.ready === false;
      });
    } else { // 'all' status
      result = baseNodes;
    }

    // Stage 3: Sort by Ready status first, then by pressure
    return result.sort((a: any, b: any) => {
      const fullA = nodes.find((n: any) => n.name === a.nodeName);
      const fullB = nodes.find((n: any) => n.name === b.nodeName);
      const aReady = fullA?.ready === true ? 1 : 0;
      const bReady = fullB?.ready === true ? 1 : 0;
      if (aReady !== bReady) return bReady - aReady;
      return 0; // maintain original relative order (pressure based)
    });
  }, [sortedNodesByPressure, nodes, filterStatus]);

  // Paginate filtered nodes
  const totalPages = Math.ceil(filteredNodes.length / NODES_PER_PAGE);
  const paginatedNodes = useMemo(() => {
    const startIndex = (currentPage - 1) * NODES_PER_PAGE;
    return filteredNodes.slice(startIndex, startIndex + NODES_PER_PAGE);
  }, [filteredNodes, currentPage]);

  // Reset to page 1 when filter changes
  const handleFilterChange = (newFilter: 'all' | 'running' | 'notReady') => {
    setFilterStatus(newFilter);
    setCurrentPage(1);
  };

  return (
    <div className="space-y-8">
      {/* Node Health Status Cards */}
      <section className="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
        <h3 className="text-lg font-bold text-gray-900 mb-6 flex items-center gap-2">
          <div className="w-8 h-8 rounded-lg bg-blue-50 flex items-center justify-center">
            <i className="fas fa-server text-blue-600"></i>
          </div>
          Node Health Overview
        </h3>

        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          {/* Ready Nodes */}
          <div className="bg-white rounded-xl shadow-sm p-5 border border-gray-200 hover:shadow-md transition-shadow">
            <div className="flex items-center gap-3 mb-3">
              <div className="w-10 h-10 rounded-lg bg-green-100 flex items-center justify-center">
                <i className="fas fa-check-double text-green-600 text-lg"></i>
              </div>
              <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Ready</span>
            </div>
            <div className="text-3xl font-bold text-gray-900">{getNodeConditionCount('ready')}</div>
            <div className="text-xs text-gray-600 mt-1">Healthy nodes</div>
          </div>

          {/* Disk Pressure */}
          <div className="bg-white rounded-xl shadow-sm p-5 border border-gray-200 hover:shadow-md transition-shadow">
            <div className="flex items-center gap-3 mb-3">
              <div className="w-10 h-10 rounded-lg bg-amber-100 flex items-center justify-center">
                <i className="fas fa-hdd text-amber-600 text-lg"></i>
              </div>
              <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Disk Pressure</span>
            </div>
            <div className="text-3xl font-bold text-gray-900">{getNodeConditionCount('diskPressure')}</div>
            <div className="text-xs text-gray-600 mt-1">Storage issues</div>
          </div>

          {/* Memory Pressure */}
          <div className="bg-white rounded-xl shadow-sm p-5 border border-gray-200 hover:shadow-md transition-shadow">
            <div className="flex items-center gap-3 mb-3">
              <div className="w-10 h-10 rounded-lg bg-red-100 flex items-center justify-center">
                <i className="fas fa-memory text-red-600 text-lg"></i>
              </div>
              <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Memory Pressure</span>
            </div>
            <div className="text-3xl font-bold text-gray-900">{getNodeConditionCount('memoryPressure')}</div>
            <div className="text-xs text-gray-600 mt-1">RAM issues</div>
          </div>

          {/* Total Nodes */}
          <div className="bg-white rounded-xl shadow-sm p-5 border border-gray-200 hover:shadow-md transition-shadow">
            <div className="flex items-center gap-3 mb-3">
              <div className="w-10 h-10 rounded-lg bg-blue-100 flex items-center justify-center">
                <i className="fas fa-server text-blue-600 text-lg"></i>
              </div>
              <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Total Nodes</span>
            </div>
            <div className="text-3xl font-bold text-gray-900">{nodes.length}</div>
            <div className="text-xs text-gray-600 mt-1">{getNodeConditionCount('ready')} running nodes</div>
          </div>
        </div>
      </section>

      {/* Node Details with Visual Capacity Bars */}
      <section className="bg-white rounded-xl shadow-sm p-6 border border-gray-200">
        <div className="flex items-center justify-between mb-6">
          <h3 className="text-lg font-bold text-gray-900 flex items-center gap-2">
            <div className="w-8 h-8 rounded-lg bg-blue-50 flex items-center justify-center">
              <i className="fas fa-network-wired text-blue-600"></i>
            </div>
            Node Resource Allocation
            <span className="text-sm font-normal text-gray-600">
              ({getNodeConditionCount('ready')} running nodes)
            </span>
          </h3>

          {/* Filter Buttons */}
          <div className="flex items-center gap-2">
            <button
              onClick={() => handleFilterChange('all')}
              className={`px-4 py-2 rounded-lg text-sm font-semibold transition-all ${filterStatus === 'all'
                ? 'bg-blue-100 text-blue-700 border border-blue-200'
                : 'bg-gray-50 text-gray-600 border border-gray-200 hover:bg-gray-100'
                }`}
            >
              All Nodes
            </button>
            <button
              onClick={() => handleFilterChange('running')}
              className={`px-4 py-2 rounded-lg text-sm font-semibold transition-all ${filterStatus === 'running'
                ? 'bg-green-100 text-green-700 border border-green-200'
                : 'bg-gray-50 text-gray-600 border border-gray-200 hover:bg-gray-100'
                }`}
            >
              <i className="fas fa-check-circle mr-1"></i>
              Running
            </button>
            <button
              onClick={() => handleFilterChange('notReady')}
              className={`px-4 py-2 rounded-lg text-sm font-semibold transition-all ${filterStatus === 'notReady'
                ? 'bg-red-100 text-red-700 border border-red-200'
                : 'bg-gray-50 text-gray-600 border border-gray-200 hover:bg-gray-100'
                }`}
            >
              <i className="fas fa-exclamation-circle mr-1"></i>
              Not Ready
            </button>
          </div>
        </div>

        <div className="space-y-4">
          {paginatedNodes.map((node: any, index: number) => {
            // Try to find full node details from multiple sources
            let fullNode = nodes.find((n: any) => n.name === node.nodeName) ||
              eksData.sortedNodesByCost?.find((n: any) => n.nodeName === node.nodeName) ||
              node;

            // Normalize usage metrics with fallbacks
            // Check all available sources (nodeMetrics, sortedNodesByPressure, sortedNodesByCost)
            const cpuUsageMillicores = fullNode.cpuUsageMillicores || node.cpuUsageMillicores || 0;
            const cpuCapacity = fullNode.cpuCapacity || node.cpuCapacity || 0;

            // Calculate CPU percent if missing but millicores present
            let cpuPercent = fullNode.cpuUsagePercent || fullNode.cpuUsage || node.cpuUsagePercent || 0;
            if ((cpuPercent === 0 || cpuPercent === undefined) && cpuUsageMillicores > 0 && cpuCapacity > 0) {
              cpuPercent = (cpuUsageMillicores / (cpuCapacity * 1000)) * 100;
            }

            const memPercent = fullNode.memoryUsagePercent || fullNode.memoryUsage || node.memoryUsagePercent || 0;
            const memUsageMB = fullNode.memoryUsageMB || node.memoryUsageMB ||
              (memPercent > 0 && (fullNode.memoryCapacity || node.memoryCapacity || 0) > 0 ? (memPercent / 100) * (fullNode.memoryCapacity || node.memoryCapacity) * 1024 : 0);

            const isHealthy = fullNode?.ready !== false && cpuPercent < 85 && memPercent < 85;

            return (
              <div
                key={index}
                className="bg-white rounded-xl p-6 border border-gray-200 hover:border-gray-300 hover:shadow-md transition-all"
              >
                <div className="flex items-start justify-between gap-6 mb-4">
                  {/* Node Info */}
                  <div className="flex items-start gap-4 flex-1 min-w-0">
                    <div className={`w-14 h-14 rounded-xl flex items-center justify-center flex-shrink-0 ${isHealthy ? 'bg-green-100' : 'bg-red-100'
                      }`}>
                      <i className={`fas fa-server text-2xl ${isHealthy ? 'text-green-600' : 'text-red-600'
                        }`}></i>
                    </div>

                    <div className="flex-1 min-w-0">
                      <h4 className="text-lg font-bold text-gray-900 mb-2 flex items-center gap-2 flex-wrap">
                        {node.nodeName}
                        <span className={`px-3 py-1 rounded-full text-xs font-bold flex items-center gap-1.5 ${fullNode?.ready ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
                          }`}>
                          <div className={`w-2 h-2 rounded-full ${fullNode?.ready ? 'bg-green-600' : 'bg-red-600'} animate-pulse`}></div>
                          {fullNode?.ready ? 'Ready' : 'Not Ready'}
                        </span>
                      </h4>

                      <div className="flex items-center gap-3 flex-wrap mb-4">
                        <span className="text-sm bg-slate-100 text-slate-700 px-3 py-1 rounded-lg font-semibold flex items-center gap-1.5">
                          <i className="fas fa-microchip text-xs"></i>
                          {fullNode?.instanceType || 'Unknown'}
                        </span>
                        {fullNode?.capacityType && (
                          <span className={`text-xs px-2 py-1 rounded-full font-semibold ${fullNode.capacityType === 'spot'
                            ? 'bg-purple-100 text-purple-700'
                            : fullNode.capacityType === 'on-demand'
                              ? 'bg-blue-100 text-blue-700'
                              : 'bg-gray-100 text-gray-700'
                            }`}>
                            {fullNode.capacityType === 'spot' ? '⚡ Spot' : fullNode.capacityType === 'on-demand' ? '💎 On-Demand' : fullNode.capacityType}
                          </span>
                        )}
                        <span className="text-sm text-gray-500 flex items-center gap-1.5">
                          <i className="fas fa-map-marker-alt text-xs"></i>
                          {fullNode?.zone || 'N/A'}
                        </span>
                        {fullNode?.kubeletVersion && (
                          <span className="text-sm text-gray-500 flex items-center gap-1.5">
                            <i className="fas fa-code-branch text-xs"></i>
                            v{fullNode.kubeletVersion}
                          </span>
                        )}
                      </div>

                      {/* AWS and K8s Metadata Badges */}
                      <div className="flex flex-wrap gap-2 mb-4">
                        {fullNode?.internalIp && (
                          <span className="text-[10px] px-2 py-0.5 bg-gray-100 text-gray-600 rounded font-mono border border-gray-200">
                            IP: {fullNode.internalIp}
                          </span>
                        )}
                        {fullNode?.instanceId && (
                          <span className="text-[10px] px-2 py-0.5 bg-gray-100 text-gray-600 rounded font-mono border border-gray-200">
                            ID: {fullNode.instanceId}
                          </span>
                        )}
                        {(fullNode?.runningPodsCount !== undefined || fullNode?.podCount !== undefined) && (
                          <span className="text-[10px] px-2 py-0.5 bg-blue-50 text-blue-700 rounded font-bold border border-blue-100">
                            <i className="fas fa-cubes mr-1"></i>
                            {fullNode.runningPodsCount ?? 0} / {fullNode.podCount ?? 0} Pods
                          </span>
                        )}
                      </div>

                      {/* Resource Metrics Grid - 2x2 Layout */}
                      <div className="grid grid-cols-2 gap-3">
                        {/* CPU Usage */}
                        <div>
                          <div className="flex items-center justify-between text-xs mb-1.5">
                            <span className="font-bold text-gray-700 flex items-center gap-1.5">
                              <i className="fas fa-microchip text-orange-500 text-[10px]"></i>
                              CPU Usage
                            </span>
                            <span className={`font-semibold text-[12px] ${cpuPercent >= 80 ? 'text-[#cf222e]' :
                              cpuPercent >= 60 ? 'text-[#9a6700]' : 'text-[#1a7f37]'
                              }`}>
                              {cpuPercent.toFixed(1)}% {cpuUsageMillicores > 0 && `(${Math.round(cpuUsageMillicores)}m)`}
                            </span>
                          </div>
                          <div className="h-1.5 bg-[#e8ecf0] rounded-full overflow-hidden">
                            <div className="h-full rounded-full transition-all duration-500"
                              style={{ width: `${Math.min(100, cpuPercent)}%`, background: cpuPercent >= 80 ? '#cf222e' : cpuPercent >= 60 ? '#9a6700' : '#2f4bde' }}
                            />
                          </div>
                        </div>

                        {/* Memory Usage */}
                        <div>
                          <div className="flex items-center justify-between text-xs mb-1.5">
                            <span className="font-semibold text-[#57606a] flex items-center gap-1.5">
                              <i className="fas fa-memory text-[11px]"></i>
                              Memory Usage
                            </span>
                            <span className="font-semibold text-sm" style={{ color: memPercent >= 80 ? '#cf222e' : memPercent >= 60 ? '#9a6700' : '#1a1f2e' }}>
                              {memPercent.toFixed(1)}% {memUsageMB > 0 && `(${Math.round(memUsageMB)} MB)`}
                            </span>
                          </div>
                          <div className="h-1.5 bg-[#e8ecf0] rounded-full overflow-hidden">
                            <div className="h-full rounded-full transition-all duration-500"
                              style={{ width: `${Math.min(100, memPercent)}%`, background: memPercent >= 80 ? '#cf222e' : memPercent >= 60 ? '#9a6700' : '#2f4bde' }}
                            />
                          </div>
                        </div>

                        {/* CPU Requests */}
                        <div>
                          <div className="flex items-center justify-between text-xs mb-1.5">
                            <span className="font-semibold text-[#57606a] flex items-center gap-1.5">
                              <i className="fas fa-chart-line text-[11px]"></i>
                              CPU Requests
                            </span>
                            <span className="font-semibold text-sm text-[#2f4bde]">
                              {fullNode?.cpuRequestsPercent !== undefined
                                ? `${fullNode.cpuRequestsPercent.toFixed(1)}% ${fullNode.cpuRequests ? `(${Math.round(fullNode.cpuRequests)}m)` : ''}`
                                : 'N/A'}
                            </span>
                          </div>
                          <div className="h-1.5 bg-[#e8ecf0] rounded-full overflow-hidden">
                            <div className="h-full bg-[#2f4bde] rounded-full transition-all duration-500"
                              style={{ width: `${Math.min(100, fullNode?.cpuRequestsPercent || 0)}%` }} />
                          </div>
                        </div>

                        {/* Memory Requests */}
                        <div>
                          <div className="flex items-center justify-between text-xs mb-1.5">
                            <span className="font-semibold text-[#57606a] flex items-center gap-1.5">
                              <i className="fas fa-chart-line text-[11px]"></i>
                              Memory Requests
                            </span>
                            <span className="font-semibold text-sm text-[#2f4bde]">
                              {fullNode?.memoryRequestsPercent !== undefined
                                ? `${fullNode.memoryRequestsPercent.toFixed(1)}% ${fullNode.memoryRequests ? `(${Math.round(fullNode.memoryRequests)} MB)` : ''}`
                                : 'N/A'}
                            </span>
                          </div>
                          <div className="h-1.5 bg-[#e8ecf0] rounded-full overflow-hidden">
                            <div className="h-full bg-[#2f4bde] rounded-full transition-all duration-500"
                              style={{ width: `${Math.min(100, fullNode?.memoryRequestsPercent || 0)}%` }} />
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>

                  {/* Right side Metadata (IAM, Subnet, SG) */}
                  <div className="hidden lg:flex flex-col gap-2 min-w-[200px] border-l border-gray-100 pl-4">
                    {fullNode?.iamRole && (
                      <div className="flex flex-col">
                        <span className="text-[10px] uppercase text-gray-400 font-bold">IAM Role</span>
                        <span className="text-xs text-gray-700 font-medium truncate max-w-[180px]" title={fullNode.iamRole}>
                          {fullNode.iamRole}
                        </span>
                      </div>
                    )}
                    {fullNode?.subnetIds && fullNode.subnetIds.length > 0 && (
                      <div className="flex flex-col">
                        <span className="text-[10px] uppercase text-gray-400 font-bold">Subnet</span>
                        <span className="text-xs text-gray-700 font-medium truncate max-w-[180px]" title={fullNode.subnetIds.join(', ')}>
                          {fullNode.subnetIds[0]}
                        </span>
                      </div>
                    )}
                    {fullNode?.securityGroupIds && fullNode.securityGroupIds.length > 0 && (
                      <div className="flex flex-col">
                        <span className="text-[10px] uppercase text-gray-400 font-bold">Security Group</span>
                        <span className="text-xs text-gray-700 font-medium truncate max-w-[180px]" title={fullNode.securityGroupIds[0]}>
                          {fullNode.securityGroupIds[0]}
                        </span>
                      </div>
                    )}
                  </div>

                  {/* Health Score */}
                  <div className="text-center flex-shrink-0 border-l border-gray-100 pl-4 min-w-[80px]">
                    <div className={`w-10 h-10 rounded-lg flex items-center justify-center text-base font-semibold mx-auto border-2 ${isHealthy ? 'border-[#aceebb] text-[#1a7f37] bg-[#dafbe1]' : 'border-[rgba(255,129,130,0.4)] text-[#cf222e] bg-[#ffebe9]'
                      }`}>
                      {isHealthy ? '✓' : '!'}
                    </div>
                    <div className="text-[10px] text-[#8b949e] mt-1.5 font-medium uppercase tracking-wider">
                      {isHealthy ? 'Healthy' : 'Issues'}
                    </div>
                  </div>
                </div>
              </div>
            );
          })}

          {
            filteredNodes.length === 0 && (
              <div className="text-center py-12 bg-[#f6f8fa] rounded-lg border border-dashed border-[#d0d7de]">
                <i className="fas fa-server text-[#8b949e] text-2xl mb-3"></i>
                <p className="text-[13px] text-[#57606a] font-medium">No nodes found matching the filter</p>
              </div>
            )
          }
        </div >

        {/* Pagination Controls */}
        {
          filteredNodes.length > NODES_PER_PAGE && (
            <div className="flex items-center justify-between mt-6 pt-6 border-t border-gray-200">
              <div className="text-sm text-gray-600">
                Showing {((currentPage - 1) * NODES_PER_PAGE) + 1} to {Math.min(currentPage * NODES_PER_PAGE, filteredNodes.length)} of {filteredNodes.length} nodes
              </div>

              <div className="flex items-center gap-2">
                <button
                  onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
                  disabled={currentPage === 1}
                  className={`px-3 py-2 rounded-lg text-sm font-semibold transition-all ${currentPage === 1
                    ? 'bg-gray-100 text-gray-400 cursor-not-allowed'
                    : 'bg-white text-gray-700 border border-gray-200 hover:bg-gray-50'
                    }`}
                >
                  <i className="fas fa-chevron-left"></i>
                </button>

                {Array.from({ length: totalPages }, (_, i) => i + 1).map(page => (
                  <button
                    key={page}
                    onClick={() => setCurrentPage(page)}
                    className={`px-3 py-2 rounded-lg text-sm font-semibold transition-all ${currentPage === page
                      ? 'bg-blue-600 text-white'
                      : 'bg-white text-gray-700 border border-gray-200 hover:bg-gray-50'
                      }`}
                  >
                    {page}
                  </button>
                ))}

                <button
                  onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))}
                  disabled={currentPage === totalPages}
                  className={`px-3 py-2 rounded-lg text-sm font-semibold transition-all ${currentPage === totalPages
                    ? 'bg-gray-100 text-gray-400 cursor-not-allowed'
                    : 'bg-white text-gray-700 border border-gray-200 hover:bg-gray-50'
                    }`}
                >
                  <i className="fas fa-chevron-right"></i>
                </button>
              </div>
            </div>
          )
        }
      </section >
    </div >
  );
};

export default EksNodesTab;
