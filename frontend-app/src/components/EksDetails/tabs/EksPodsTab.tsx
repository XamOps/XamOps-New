/**
 * EKS Pods Tab
 *
 * Provides a detailed view of pod resources, health status, and container metrics.
 * Features:
 * - Search by name/namespace
 * - Filter by namespace
 * - Sort by CPU, Memory, Restarts, etc.
 * - Expandable rows for container-level details
 */

import React, { useState, useMemo } from 'react';

const EksPodsTab: React.FC<{ eksData: any }> = ({ eksData }) => {
  const { dashboard, getPodsCountByPhase } = eksData;
  const pods = dashboard?.workloadHealth?.pods || [];

  // Local state for sorting and filtering
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedNamespace, setSelectedNamespace] = useState('all');
  const [sortBy, setSortBy] = useState<'name' | 'cpuUsage' | 'memoryUsage' | 'restarts'>('cpuUsage');
  const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>('desc');
  const [expandedPod, setExpandedPod] = useState<string | null>(null);

  // Helper: Get unique namespaces
  const namespaces = useMemo(() => {
    const nsSet = new Set<string>();
    pods.forEach((p: any) => p.namespace && nsSet.add(p.namespace));
    return ['all', ...Array.from(nsSet).sort()];
  }, [pods]);

  // Helper: Get Pod CPU (sum container CPU if pod level missing)
  const getPodCpuUsage = (pod: any): number => {
    if ((pod.cpuUsage || 0) > 0) return pod.cpuUsage;
    return (pod.containers || []).reduce((sum: number, c: any) => sum + (c.cpuUsage || 0), 0);
  };

  // Helper: Get Pod Memory (sum container memory if pod level missing)
  const getPodMemoryUsage = (pod: any): number => {
    if ((pod.memoryUsage || 0) > 0) return pod.memoryUsage;
    return (pod.containers || []).reduce((sum: number, c: any) => sum + (c.memoryUsage || 0), 0);
  };

  // Filter and Sort Pods
  const processedPods = useMemo(() => {
    let result = [...pods];

    // Filter by Namespace
    if (selectedNamespace !== 'all') {
      result = result.filter(p => p.namespace === selectedNamespace);
    }

    // Filter by Search Query
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      result = result.filter(p =>
        (p.name?.toLowerCase() || '').includes(q) ||
        (p.namespace?.toLowerCase() || '').includes(q)
      );
    }

    // Sort
    result.sort((a, b) => {
      let valA: number | string = 0;
      let valB: number | string = 0;

      switch (sortBy) {
        case 'name':
          valA = a.name || '';
          valB = b.name || '';
          break;
        case 'cpuUsage':
          valA = getPodCpuUsage(a);
          valB = getPodCpuUsage(b);
          break;
        case 'memoryUsage':
          valA = getPodMemoryUsage(a);
          valB = getPodMemoryUsage(b);
          break;
        case 'restarts':
          valA = a.restarts || 0;
          valB = b.restarts || 0;
          break;
      }

      if (valA < valB) return sortOrder === 'asc' ? -1 : 1;
      if (valA > valB) return sortOrder === 'asc' ? 1 : -1;
      return 0;
    });

    return result;
  }, [pods, selectedNamespace, searchQuery, sortBy, sortOrder]);

  return (
    <div className="space-y-6">
      {/* 1. Status Summary Cards */}
      <div className="grid grid-cols-4 gap-4">
        <div className="bg-green-50 rounded-lg p-4 text-center border border-green-100">
          <div className="text-3xl font-black text-green-600">{getPodsCountByPhase('Running')}</div>
          <div className="text-sm text-green-700 mt-1 font-semibold">Running</div>
        </div>
        <div className="bg-amber-50 rounded-lg p-4 text-center border border-amber-100">
          <div className="text-3xl font-black text-amber-600">{getPodsCountByPhase('Pending')}</div>
          <div className="text-sm text-amber-700 mt-1 font-semibold">Pending</div>
        </div>
        <div className="bg-red-50 rounded-lg p-4 text-center border border-red-100">
          <div className="text-3xl font-black text-red-600">{getPodsCountByPhase('Failed')}</div>
          <div className="text-sm text-red-700 mt-1 font-semibold">Failed</div>
        </div>
        <div className="bg-blue-50 rounded-lg p-4 text-center border border-blue-100">
          <div className="text-3xl font-black text-blue-600">{pods.length}</div>
          <div className="text-sm text-blue-700 mt-1 font-semibold">Total Pods</div>
        </div>
      </div>

      {/* 2. Pod List with Filters */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-100 p-6">
        <div className="flex flex-wrap items-center justify-between gap-4 mb-6">
          <h3 className="text-xl font-bold text-gray-900 flex items-center gap-3">
            <i className="fas fa-box text-indigo-500"></i>
            Pod Resource Details
            <span className="ml-2 px-3 py-1 bg-indigo-100 text-indigo-700 rounded-lg text-xs font-bold">
              LIVE DATA
            </span>
          </h3>

          {/* Filters */}
          <div className="flex items-center gap-3 flex-wrap">
            {/* Search */}
            <div className="relative">
              <i className="fas fa-search absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400"></i>
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search pods..."
                className="pl-10 pr-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 text-sm"
              />
            </div>

            {/* Namespace Filter */}
            <select
              value={selectedNamespace}
              onChange={(e) => setSelectedNamespace(e.target.value)}
              className="px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 text-sm font-medium"
            >
              <option value="all">All Namespaces</option>
              {namespaces.filter(ns => ns !== 'all').map(ns => (
                <option key={ns} value={ns}>{ns}</option>
              ))}
            </select>

            {/* Sort By */}
            <select
              value={sortBy}
              onChange={(e) => setSortBy(e.target.value as any)}
              className="px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 text-sm font-medium"
            >
              <option value="name">Name</option>
              <option value="cpuUsage">CPU Usage</option>
              <option value="memoryUsage">Memory Usage</option>
              <option value="restarts">Restarts</option>
            </select>

            {/* Sort Order */}
            <button
              onClick={() => setSortOrder(prev => prev === 'asc' ? 'desc' : 'asc')}
              className="px-3 py-2 bg-gray-100 hover:bg-gray-200 rounded-lg transition-colors border border-gray-200"
            >
              <i className={`fas ${sortOrder === 'asc' ? 'fa-sort-amount-up' : 'fa-sort-amount-down'} text-gray-600`}></i>
            </button>
          </div>
        </div>

        {/* Pod Cards List */}
        <div className="space-y-4 max-h-[800px] overflow-y-auto pr-2 custom-scrollbar">
          {processedPods.map((pod: any) => {
            const cpuUsage = getPodCpuUsage(pod);
            const memUsage = getPodMemoryUsage(pod);
            const isExpanded = expandedPod === pod.name;

            return (
              <div key={pod.name} className="bg-gray-50 rounded-xl border border-gray-200 hover:border-indigo-300 transition-all overflow-hidden">
                {/* Clickable Header */}
                <div
                  className="p-5 cursor-pointer flex items-center justify-between gap-4"
                  onClick={() => setExpandedPod(isExpanded ? null : pod.name)}
                >
                  <div className="flex items-center gap-4 flex-1 min-w-0">
                    <div className="flex-shrink-0">
                      <i className={`fas fa-cube text-2xl ${pod.phase === 'Running' ? 'text-green-500' :
                        pod.phase === 'Pending' ? 'text-amber-500' : 'text-red-500'
                        }`}></i>
                    </div>
                    <div className="flex-1 min-w-0">
                      <h4 className="font-bold text-gray-900 truncate">{pod.name}</h4>
                      <div className="flex items-center gap-2 mt-1 flex-wrap">
                        <span className="text-xs bg-blue-100 text-blue-700 px-2 py-1 rounded font-semibold">
                          {pod.namespace}
                        </span>
                        <span className="text-xs bg-gray-200 text-gray-700 px-2 py-1 rounded font-mono">
                          {pod.nodeName || pod.node}
                        </span>
                        <span className="text-xs text-gray-500">
                          {(pod.containers?.length || 0)} containers
                        </span>
                        <span className={`text-xs font-semibold ${pod.phase === 'Running' ? 'text-green-600' : 'text-amber-600'
                          }`}>
                          {pod.phase}
                        </span>
                      </div>
                    </div>
                  </div>

                  {/* Resource Bars (Desktop) */}
                  <div className="hidden md:flex items-center gap-6 flex-shrink-0">
                    {/* CPU */}
                    <div className="w-32">
                      <div className="flex items-center justify-between text-xs mb-1">
                        <span className="text-gray-600">CPU</span>
                        <span className="font-bold text-gray-900">{cpuUsage.toFixed(2)}m</span>
                      </div>
                      <div className="h-2 bg-gray-200 rounded-full overflow-hidden">
                        <div
                          className="h-full bg-gradient-to-r from-blue-400 to-blue-600 rounded-full"
                          style={{
                            width: `${pod.cpuLimit > 0 ? Math.min(100, (cpuUsage / pod.cpuLimit * 100)) : (cpuUsage > 0 ? 50 : 0)}%`
                          }}
                        ></div>
                      </div>
                    </div>

                    {/* Memory */}
                    <div className="w-32">
                      <div className="flex items-center justify-between text-xs mb-1">
                        <span className="text-gray-600">Memory</span>
                        <span className="font-bold text-gray-900">{memUsage.toFixed(0)} Mi</span>
                      </div>
                      <div className="h-2 bg-gray-200 rounded-full overflow-hidden">
                        <div
                          className="h-full bg-gradient-to-r from-purple-400 to-purple-600 rounded-full"
                          style={{
                            width: `${pod.memoryLimit > 0 ? Math.min(100, (memUsage / pod.memoryLimit * 100)) : (memUsage > 0 ? 50 : 0)}%`
                          }}
                        ></div>
                      </div>
                    </div>

                    <i className={`fas fa-chevron-down text-gray-400 transition-transform ${isExpanded ? 'rotate-180' : ''}`}></i>
                  </div>
                </div>

                {/* Expanded Details */}
                {isExpanded && (
                  <div className="px-5 pb-5 pt-2 border-t border-gray-200 bg-white">
                    <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-6 mt-4">
                      {/* Pod Metadata */}
                      <div className="space-y-3">
                        <h5 className="font-bold text-gray-900 text-sm uppercase tracking-wide flex items-center gap-2">
                          <i className="fas fa-info-circle text-indigo-500"></i>
                          Pod Information
                        </h5>
                        <div className="grid grid-cols-2 gap-3 text-sm">
                          <div><span className="text-gray-500">Phase:</span> <span className="font-semibold ml-2">{pod.phase}</span></div>
                          <div><span className="text-gray-500">IP:</span> <span className="font-mono font-semibold ml-2">{pod.podIp || 'N/A'}</span></div>
                          <div>
                            <span className="text-gray-500">Ready:</span>
                            <span className={`font-semibold ml-2 ${pod.ready ? 'text-green-600' : 'text-red-600'}`}>
                              {pod.ready ? 'Yes' : 'No'}
                            </span>
                          </div>
                          <div>
                            <span className="text-gray-500">Restarts:</span>
                            <span className={`font-semibold ml-2 ${(pod.restarts || 0) > 0 ? 'text-amber-600' : 'text-green-600'}`}>
                              {pod.restarts || 0}
                            </span>
                          </div>
                          <div><span className="text-gray-500">Age:</span> <span className="font-semibold ml-2">{pod.age || 'N/A'}</span></div>
                        </div>
                      </div>

                      {/* Explicit Resource Usage Text */}
                      <div className="space-y-3">
                        <h5 className="font-bold text-gray-900 text-sm uppercase tracking-wide flex items-center gap-2">
                          <i className="fas fa-chart-bar text-indigo-500"></i>
                          Resource Usage
                        </h5>
                        <div className="grid grid-cols-2 gap-4 text-sm">
                          <div className="bg-gray-50 p-3 rounded-lg">
                            <div className="text-gray-500 text-xs uppercase mb-1">CPU Request</div>
                            <div className="font-mono font-bold">{(pod.cpuRequest || 0).toFixed(0)}m</div>
                          </div>
                          <div className="bg-gray-50 p-3 rounded-lg">
                            <div className="text-gray-500 text-xs uppercase mb-1">Memory Request</div>
                            <div className="font-mono font-bold">{(pod.memoryRequest || 0).toFixed(0)} Mi</div>
                          </div>
                        </div>
                      </div>
                    </div>

                    {/* Container List */}
                    <div className="mt-4">
                      <h5 className="font-bold text-gray-900 text-sm uppercase tracking-wide mb-3 flex items-center gap-2">
                        <i className="fas fa-boxes text-indigo-500"></i>
                        Containers ({pod.containers?.length || 0})
                      </h5>
                      <div className="space-y-2">
                        {(pod.containers || []).map((container: any) => (
                          <div key={container.name} className="p-4 bg-gray-50 rounded-lg border border-gray-200">
                            <div className="flex items-start justify-between gap-4 mb-3">
                              <div className="flex-1 min-w-0">
                                <div className="flex items-center gap-2 mb-1">
                                  <i className="fas fa-cube text-indigo-500"></i>
                                  <span className="font-bold text-gray-900">{container.name}</span>
                                  <span className={`text-xs px-2 py-1 rounded flex items-center gap-1 ${container.running ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
                                    }`}>
                                    <i className="fas fa-circle text-[8px]"></i>
                                    {container.running ? 'Running' : 'Stopped'}
                                  </span>
                                </div>
                                <p className="text-xs text-gray-500 font-mono truncate">{container.image}</p>
                              </div>
                              <div className="text-right">
                                <span className="text-xs text-gray-500">Restarts:</span>
                                <span className={`text-sm font-bold ml-1 ${(container.restarts || 0) > 0 ? 'text-amber-600' : 'text-green-600'}`}>
                                  {container.restarts || 0}
                                </span>
                              </div>
                            </div>
                            <div className="grid grid-cols-3 gap-4 text-sm">
                              <div>
                                <span className="text-gray-500 text-xs">CPU</span>
                                <p className="font-bold text-gray-900">{(container.cpuUsage || 0).toFixed(2)} m</p>
                              </div>
                              <div>
                                <span className="text-gray-500 text-xs">Memory</span>
                                <p className="font-bold text-gray-900">{(container.memoryUsage || 0).toFixed(0)} Mi</p>
                              </div>
                              <div>
                                <span className="text-gray-500 text-xs">Filesystem</span>
                                <p className="font-bold text-gray-900">{(container.fsUsage || 0).toFixed(2)} GB</p>
                              </div>
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>
                  </div>
                )}
              </div>
            );
          })}

          {processedPods.length === 0 && (
            <div className="text-center py-12 bg-gray-50 rounded-xl border border-dashed border-gray-300">
              <i className="fas fa-filter text-gray-300 text-4xl mb-3"></i>
              <p className="text-gray-500 font-medium">No pods match your filters</p>
              <button
                onClick={() => { setSearchQuery(''); setSelectedNamespace('all'); }}
                className="mt-3 text-indigo-600 hover:text-indigo-800 text-sm font-semibold"
              >
                Clear Filters
              </button>
            </div>
          )}
        </div>
      </div >
    </div >
  );
};

export default EksPodsTab;
