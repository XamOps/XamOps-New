/**
 * EKS Pods Tab - Pro Max Edition
 *
 * High-density monitoring table with real-time metrics, sticky headers,
 * and professional status indicators.
 */

import React, { useState, useMemo, useEffect, useRef } from 'react';
import { Chart, registerables } from 'chart.js';

Chart.register(...registerables);

const ITEMS_PER_PAGE = 10;

const EksPodsTab: React.FC<{ eksData: any }> = ({ eksData }) => {
  const { dashboard, getPodsCountByPhase } = eksData;
  const pods = dashboard?.workloadHealth?.pods || [];

  // Local state
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedNamespace, setSelectedNamespace] = useState('all');
  const [phaseFilter, setPhaseFilter] = useState<'all' | 'Running' | 'Pending' | 'Failed'>('all');
  const [sortBy, setSortBy] = useState<'name' | 'cpuUsage' | 'memoryUsage' | 'restarts' | 'age'>('cpuUsage');
  const [sortOrder, setSortOrder] = useState<'desc' | 'asc'>('desc');
  const [expandedPod, setExpandedPod] = useState<string | null>(null);
  const [currentPage, setCurrentPage] = useState(0);

  // Chart refs
  const cpuChartRef = useRef<HTMLCanvasElement>(null);
  const memoryChartRef = useRef<HTMLCanvasElement>(null);
  const cpuChartInstance = useRef<Chart | null>(null);
  const memoryChartInstance = useRef<Chart | null>(null);

  // Get pod resource metrics
  const podResourceMetrics = dashboard?.podResourceMetrics;

  // Helper: Get unique namespaces
  const namespaces = useMemo(() => {
    const nsSet = new Set<string>();
    pods.forEach((p: any) => p.namespace && nsSet.add(p.namespace));
    return ['all', ...Array.from(nsSet).sort()];
  }, [pods]);

  // Filter and Sort Pods
  const processedPods = useMemo(() => {
    let result = [...pods];

    if (phaseFilter !== 'all') {
      result = result.filter(p => p.phase === phaseFilter);
    }

    if (selectedNamespace !== 'all') {
      result = result.filter(p => p.namespace === selectedNamespace);
    }

    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      result = result.filter(p =>
        (p.name?.toLowerCase() || '').includes(q) ||
        (p.namespace?.toLowerCase() || '').includes(q) ||
        (p.nodeName?.toLowerCase() || '').includes(q) ||
        (p.node?.toLowerCase() || '').includes(q)
      );
    }

    result.sort((a, b) => {
      let valA: any;
      let valB: any;

      switch (sortBy) {
        case 'name':
          valA = a.name || '';
          valB = b.name || '';
          break;
        case 'cpuUsage':
          valA = a.cpuUsage || 0;
          valB = b.cpuUsage || 0;
          break;
        case 'memoryUsage':
          valA = a.memoryUsage || 0;
          valB = b.memoryUsage || 0;
          break;
        case 'restarts':
          valA = a.restarts || 0;
          valB = b.restarts || 0;
          break;
        case 'age':
          valA = a.age || '';
          valB = b.age || '';
          break;
        default:
          valA = 0;
          valB = 0;
      }

      if (valA < valB) return sortOrder === 'asc' ? -1 : 1;
      if (valA > valB) return sortOrder === 'asc' ? 1 : -1;
      return 0;
    });

    return result;
  }, [pods, phaseFilter, selectedNamespace, searchQuery, sortBy, sortOrder]);

  // Pagination Logic
  const paginatedPods = useMemo(() => {
    return processedPods.slice(
      currentPage * ITEMS_PER_PAGE,
      (currentPage + 1) * ITEMS_PER_PAGE
    );
  }, [processedPods, currentPage]);

  const totalPages = Math.ceil(processedPods.length / ITEMS_PER_PAGE);

  // Reset expansion and page when filters change
  useEffect(() => {
    setExpandedPod(null);
    setCurrentPage(0);
  }, [phaseFilter, selectedNamespace, searchQuery]);



  // Initialize charts
  useEffect(() => {
    if (!podResourceMetrics || !cpuChartRef.current || !memoryChartRef.current) return;

    // CPU Chart
    const cpuCtx = cpuChartRef.current.getContext('2d');
    if (cpuCtx) {
      if (cpuChartInstance.current) {
        cpuChartInstance.current.destroy();
      }

      cpuChartInstance.current = new Chart(cpuCtx, {
        type: 'bar',
        data: {
          labels: ['Cluster Capacity', 'Pod Requests', 'Pod Limits', 'Current Usage'],
          datasets: [{
            data: [
              podResourceMetrics.totalCpuCapacity,
              podResourceMetrics.totalCpuRequests,
              podResourceMetrics.totalCpuLimits,
              podResourceMetrics.totalCpuUsage
            ],
            backgroundColor: ['#9CA3AF', '#3B82F6', '#F59E0B', '#10B981'],
            borderRadius: 8,
            barThickness: 60
          }]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: {
            legend: { display: false },
            tooltip: {
              callbacks: {
                label: (context) => `${context.parsed.y.toFixed(2)} millicores`
              }
            }
          },
          scales: {
            y: {
              beginAtZero: true,
              grid: { color: '#F3F4F6', drawBorder: false },
              ticks: { font: { size: 11 } }
            },
            x: {
              grid: { display: false },
              ticks: { font: { size: 11, weight: '600' } }
            }
          }
        }
      });
    }

    // Memory Chart
    const memoryCtx = memoryChartRef.current.getContext('2d');
    if (memoryCtx) {
      if (memoryChartInstance.current) {
        memoryChartInstance.current.destroy();
      }

      memoryChartInstance.current = new Chart(memoryCtx, {
        type: 'bar',
        data: {
          labels: ['Cluster Capacity', 'Pod Requests', 'Pod Limits', 'Current Usage'],
          datasets: [{
            data: [
              podResourceMetrics.totalMemoryCapacity / 1024, // Convert to GB
              podResourceMetrics.totalMemoryRequests / 1024,
              podResourceMetrics.totalMemoryLimits / 1024,
              podResourceMetrics.totalMemoryUsage / 1024
            ],
            backgroundColor: ['#9CA3AF', '#3B82F6', '#F59E0B', '#10B981'],
            borderRadius: 8,
            barThickness: 60
          }]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: {
            legend: { display: false },
            tooltip: {
              callbacks: {
                label: (context) => `${context.parsed.y.toFixed(2)} GB`
              }
            }
          },
          scales: {
            y: {
              beginAtZero: true,
              grid: { color: '#F3F4F6', drawBorder: false },
              ticks: { font: { size: 11 } }
            },
            x: {
              grid: { display: false },
              ticks: { font: { size: 11, weight: '600' } }
            }
          }
        }
      });
    }

    return () => {
      cpuChartInstance.current?.destroy();
      memoryChartInstance.current?.destroy();
    };
  }, [podResourceMetrics]);

  const resetFilters = () => {
    setSearchQuery('');
    setSelectedNamespace('all');
    setPhaseFilter('all');
    setCurrentPage(0);
    setExpandedPod(null);
  };

  if (!dashboard) return null;

  return (
    <div className="space-y-6">
      {/* 1. Professional Status Overview */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        {[
          { label: 'Running', count: getPodsCountByPhase('Running'), color: 'emerald', icon: 'fa-play-circle' },
          { label: 'Pending', count: getPodsCountByPhase('Pending'), color: 'amber', icon: 'fa-hourglass-half' },
          { label: 'Failed', count: getPodsCountByPhase('Failed'), color: 'rose', icon: 'fa-exclamation-triangle' },
          { label: 'Total Pods', count: pods.length, color: 'blue', icon: 'fa-box' }
        ].map((stat) => (
          <div key={stat.label} className="bg-white p-5 rounded-2xl border border-gray-100 shadow-sm hover:shadow-md transition-shadow duration-300">
            <div className="flex items-center justify-between mb-2">
              <span className="text-[11px] font-bold text-gray-400 uppercase tracking-widest">{stat.label}</span>
              <div className={`w-8 h-8 rounded-lg flex items-center justify-center bg-${stat.color}-50 text-${stat.color}-600`}>
                <i className={`fas ${stat.icon} text-sm`}></i>
              </div>
            </div>
            <div className="text-3xl font-black text-gray-900">{stat.count}</div>
          </div>
        ))}
      </div>

      {/* Resource Requests vs Limits Overview Charts */}
      {podResourceMetrics && (
        <div className="bg-white rounded-xl shadow-sm p-6 border border-gray-200">
          <h3 className="text-lg font-bold text-gray-900 mb-6 flex items-center gap-2">
            <div className="w-8 h-8 rounded-lg bg-blue-50 flex items-center justify-center">
              <i className="fas fa-chart-bar text-blue-600"></i>
            </div>
            Resource Requests vs Limits Overview
          </h3>

          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <div>
              <h4 className="text-sm font-semibold text-gray-700 mb-3">CPU Resources</h4>
              <div style={{ height: '300px' }}>
                <canvas ref={cpuChartRef}></canvas>
              </div>
            </div>

            <div>
              <h4 className="text-sm font-semibold text-gray-700 mb-3">Memory Resources</h4>
              <div style={{ height: '300px' }}>
                <canvas ref={memoryChartRef}></canvas>
              </div>
            </div>
          </div>

          {/* Summary cards below charts */}
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mt-6 pt-6 border-t border-gray-200">
            <div className="text-center">
              <div className="text-xs text-gray-600 mb-1">Pods Without Requests</div>
              <div className="text-2xl font-bold text-amber-600">{podResourceMetrics.podsWithoutRequests}</div>
            </div>
            <div className="text-center">
              <div className="text-xs text-gray-600 mb-1">Pods Without Limits</div>
              <div className="text-2xl font-bold text-red-600">{podResourceMetrics.podsWithoutLimits}</div>
            </div>
            <div className="text-center">
              <div className="text-xs text-gray-600 mb-1">Request Utilization</div>
              <div className="text-2xl font-bold text-blue-600">
                {podResourceMetrics.totalCpuRequests > 0
                  ? ((podResourceMetrics.totalCpuUsage / podResourceMetrics.totalCpuRequests) * 100).toFixed(1)
                  : '0.0'}%
              </div>
            </div>
            <div className="text-center">
              <div className="text-xs text-gray-600 mb-1">Limit Utilization</div>
              <div className="text-2xl font-bold text-orange-600">
                {podResourceMetrics.totalCpuLimits > 0
                  ? ((podResourceMetrics.totalCpuUsage / podResourceMetrics.totalCpuLimits) * 100).toFixed(1)
                  : '0.0'}%
              </div>
            </div>
          </div>
        </div>
      )}

      {/* 2. Sticky Filtering controls */}
      <div className="sticky top-0 z-20 bg-gray-50/80 backdrop-blur-md py-4">
        <div className="bg-white p-2 rounded-2xl border border-gray-200 shadow-sm flex flex-col lg:flex-row gap-3 items-center">
          <div className="flex bg-gray-100 p-1 rounded-xl w-full lg:w-auto shrink-0">
            {['all', 'Running', 'Pending', 'Failed'].map((p) => (
              <button
                key={p}
                onClick={() => setPhaseFilter(p as any)}
                className={`flex-1 lg:flex-none px-6 py-2 rounded-lg text-sm font-bold transition-all ${phaseFilter === p
                  ? 'bg-white text-blue-600 shadow-sm'
                  : 'text-gray-500 hover:text-gray-900 hover:bg-white/50'
                  }`}
              >
                {p.charAt(0).toUpperCase() + p.slice(1)}
              </button>
            ))}
          </div>

          <div className="h-8 w-px bg-gray-200 hidden lg:block mx-1"></div>

          <div className="relative flex-1 w-full min-w-[200px]">
            <i className="fas fa-search absolute left-4 top-1/2 -translate-y-1/2 text-gray-400"></i>
            <input
              type="text"
              placeholder="Search by pod name, node, or namespace..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full pl-12 pr-4 py-2.5 bg-transparent border-none focus:ring-0 text-sm font-medium text-gray-900 placeholder:text-gray-400"
            />
          </div>

          <div className="h-8 w-px bg-gray-200 hidden lg:block mx-1"></div>

          <div className="flex items-center gap-3 w-full lg:w-auto px-2">
            <select
              value={selectedNamespace}
              onChange={(e) => setSelectedNamespace(e.target.value)}
              className="bg-transparent border-none text-sm font-bold text-gray-700 focus:ring-0 cursor-pointer min-w-[140px]"
            >
              <option value="all">All Namespaces</option>
              {namespaces.filter(ns => ns !== 'all').map(ns => (
                <option key={ns} value={ns}>{ns}</option>
              ))}
            </select>

            <div className="h-8 w-px bg-gray-200 hidden lg:block"></div>

            <button
              onClick={() => setSortOrder(prev => prev === 'asc' ? 'desc' : 'asc')}
              className="p-2 text-gray-400 hover:text-blue-600 transition-colors"
              title={`Sort ${sortOrder === 'asc' ? 'Descending' : 'Ascending'}`}
            >
              <i className={`fas ${sortOrder === 'asc' ? 'fa-sort-amount-up' : 'fa-sort-amount-down'} text-lg`}></i>
            </button>
          </div>
        </div>
      </div>

      {/* 3. Professional Monitoring Table */}
      <div className="bg-white rounded-3xl border border-gray-100 shadow-xl overflow-hidden">
        <div className="overflow-x-auto no-scrollbar">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="bg-gray-50/50 border-b border-gray-100">
                <th className="px-6 py-4 text-xs font-black uppercase tracking-widest text-gray-400 w-20">Status</th>
                <th
                  className="px-6 py-4 text-xs font-black uppercase tracking-widest text-gray-400 cursor-pointer hover:text-blue-600 transition-colors"
                  onClick={() => setSortBy('name')}
                >
                  Pod Name {sortBy === 'name' && <i className={`fas fa-caret-${sortOrder === 'asc' ? 'up' : 'down'} ml-1`}></i>}
                </th>
                <th className="px-6 py-4 text-xs font-black uppercase tracking-widest text-gray-400">Node</th>
                <th
                  className="px-6 py-4 text-xs font-black uppercase tracking-widest text-gray-400 cursor-pointer hover:text-blue-600 transition-colors"
                  onClick={() => setSortBy('restarts')}
                >
                  Restarts {sortBy === 'restarts' && <i className={`fas fa-caret-${sortOrder === 'asc' ? 'up' : 'down'} ml-1`}></i>}
                </th>
                <th
                  className="px-6 py-4 text-xs font-black uppercase tracking-widest text-gray-400 cursor-pointer hover:text-blue-600 transition-colors"
                  onClick={() => setSortBy('cpuUsage')}
                >
                  CPU Usage {sortBy === 'cpuUsage' && <i className={`fas fa-caret-${sortOrder === 'asc' ? 'up' : 'down'} ml-1`}></i>}
                </th>
                <th
                  className="px-6 py-4 text-xs font-black uppercase tracking-widest text-gray-400 cursor-pointer hover:text-blue-600 transition-colors"
                  onClick={() => setSortBy('memoryUsage')}
                >
                  Memory {sortBy === 'memoryUsage' && <i className={`fas fa-caret-${sortOrder === 'asc' ? 'up' : 'down'} ml-1`}></i>}
                </th>
                <th
                  className="px-6 py-4 text-xs font-black uppercase tracking-widest text-gray-400 cursor-pointer hover:text-blue-600 transition-colors"
                  onClick={() => setSortBy('age')}
                >
                  Age {sortBy === 'age' && <i className={`fas fa-caret-${sortOrder === 'asc' ? 'up' : 'down'} ml-1`}></i>}
                </th>
                <th className="px-6 py-4 text-xs font-black uppercase tracking-widest text-gray-400 w-10"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-50">
              {paginatedPods.map((pod: any) => {
                const isExpanded = expandedPod === pod.name;
                const cpuUsage = pod.cpuUsage || 0;
                const memUsage = pod.memoryUsage || 0;
                const cpuLimit = pod.cpuLimit || 0;
                const memLimit = pod.memoryLimit || 0;
                const isRunning = pod.phase === 'Running';
                const isPending = pod.phase === 'Pending';

                return (
                  <React.Fragment key={pod.name}>
                    <tr
                      className={`group hover:bg-blue-50/20 transition-all cursor-pointer ${isExpanded ? 'bg-blue-50/40' : ''}`}
                      onClick={() => setExpandedPod(isExpanded ? null : pod.name)}
                    >
                      <td className="px-6 py-4">
                        <div className={`p-1.5 rounded-full flex items-center justify-center w-8 h-8 ${isRunning ? 'bg-emerald-50 text-emerald-500 shadow-sm shadow-emerald-200' :
                          isPending ? 'bg-amber-50 text-amber-500' : 'bg-rose-50 text-rose-500'
                          }`}>
                          <i className={`fas ${isRunning ? 'fa-check-circle' : isPending ? 'fa-clock' : 'fa-times-circle'} text-sm`}></i>
                        </div>
                      </td>
                      <td className="px-6 py-4">
                        <div className="flex flex-col max-w-[280px]">
                          <span className="text-sm font-bold text-gray-900 truncate" title={pod.name}>{pod.name}</span>
                          <span className="text-xs font-medium text-gray-500 uppercase tracking-tight">{pod.namespace}</span>
                        </div>
                      </td>
                      <td className="px-6 py-4">
                        <div className="flex items-center gap-2 text-sm font-semibold text-gray-600">
                          <i className="fas fa-microchip text-gray-300 text-xs"></i>
                          <span className="truncate max-w-[120px]">{pod.nodeName || pod.node || 'N/A'}</span>
                        </div>
                      </td>
                      <td className="px-6 py-4 text-center">
                        <span className={`text-sm font-black ${pod.restarts > 5 ? 'text-rose-600' : 'text-gray-900'}`}>
                          {pod.restarts || 0}
                        </span>
                      </td>
                      <td className="px-6 py-4">
                        <div className="flex flex-col gap-1.5 w-28">
                          <div className="flex justify-between items-center text-xs font-black">
                            <span className="text-gray-900">{cpuUsage.toFixed(1)}m</span>
                            <span className="text-gray-400">{cpuLimit > 0 ? `${((cpuUsage / cpuLimit) * 100).toFixed(0)}%` : ''}</span>
                          </div>
                          <div className="h-1.5 w-full bg-gray-100 rounded-full overflow-hidden">
                            <div
                              className={`h-full rounded-full transition-all duration-700 ${cpuUsage > 800 ? 'bg-rose-500' : 'bg-blue-500'}`}
                              style={{ width: `${cpuLimit > 0 ? Math.min(100, (cpuUsage / cpuLimit) * 100) : (cpuUsage > 0 ? 30 : 5)}%` }}
                            ></div>
                          </div>
                        </div>
                      </td>
                      <td className="px-6 py-4">
                        <div className="flex flex-col gap-1.5 w-28">
                          <div className="flex justify-between items-center text-xs font-black">
                            <span className="text-gray-900">{memUsage.toFixed(0)}Mi</span>
                            <span className="text-gray-400">{memLimit > 0 ? `${((memUsage / memLimit) * 100).toFixed(0)}%` : ''}</span>
                          </div>
                          <div className="h-1.5 w-full bg-gray-100 rounded-full overflow-hidden">
                            <div
                              className={`h-full rounded-full transition-all duration-700 ${memUsage > 1024 ? 'bg-amber-500' : 'bg-indigo-500'}`}
                              style={{ width: `${memLimit > 0 ? Math.min(100, (memUsage / memLimit) * 100) : (memUsage > 0 ? 30 : 5)}%` }}
                            ></div>
                          </div>
                        </div>
                      </td>
                      <td className="px-6 py-4">
                        <span className="text-xs font-bold text-gray-600 bg-gray-50 px-2 py-1 rounded-md">{pod.age || 'N/A'}</span>
                      </td>
                      <td className="px-6 py-4 text-right">
                        <i className={`fas fa-chevron-right text-gray-200 transition-transform duration-300 ${isExpanded ? 'rotate-90 text-blue-400' : ''}`}></i>
                      </td>
                    </tr>

                    {/* Expansion Drawer */}
                    {isExpanded && (
                      <tr>
                        <td colSpan={8} className="p-0 border-none">
                          <div className="px-8 py-6 bg-gray-50/50 border-y border-gray-100 shadow-inner">
                            <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
                              {/* Metadata Card */}
                              <div className="bg-white p-5 rounded-2xl border border-gray-100 shadow-sm space-y-4">
                                <h4 className="text-xs font-black text-blue-600 uppercase tracking-widest flex items-center gap-2">
                                  <i className="fas fa-fingerprint"></i> Pod Identification
                                </h4>
                                <div className="space-y-3">
                                  <div className="flex justify-between group/meta">
                                    <span className="text-xs font-medium text-gray-400 uppercase tracking-tight">IP Address</span>
                                    <span className="text-xs font-mono font-black text-gray-900 group-hover/meta:text-blue-600 transition-colors uppercase">{pod.podIp || 'N/A'}</span>
                                  </div>
                                  <div className="flex justify-between group/meta">
                                    <span className="text-xs font-medium text-gray-400 uppercase tracking-tight">Owner Type</span>
                                    <span className="text-xs font-black text-gray-900 uppercase tracking-tighter">{pod.ownerKind || 'N/A'}</span>
                                  </div>
                                  <div className="flex justify-between group/meta">
                                    <span className="text-xs font-medium text-gray-400 uppercase tracking-tight">Owner Name</span>
                                    <span className="text-xs font-black text-blue-500 truncate max-w-[140px]">{pod.ownerName || 'N/A'}</span>
                                  </div>
                                  <div className="flex justify-between group/meta">
                                    <span className="text-xs font-medium text-gray-400 uppercase tracking-tight">QoS Class</span>
                                    <span className="px-2 py-0.5 bg-gray-100 rounded text-xs font-black text-gray-600 uppercase">{pod.qosClass || 'Guaranteed'}</span>
                                  </div>
                                </div>
                              </div>

                              {/* Resource Details Card */}
                              <div className="lg:col-span-2 bg-white p-5 rounded-2xl border border-gray-100 shadow-sm">
                                <h4 className="text-xs font-black text-indigo-600 uppercase tracking-widest mb-4 flex items-center gap-2">
                                  <i className="fas fa-chart-line"></i> Container Utilization ({pod.containers?.length || 0})
                                </h4>
                                <div className="space-y-2 max-h-[240px] overflow-y-auto pr-2 no-scrollbar">
                                  {(pod.containers || []).map((container: any) => {
                                    const status = pod.containerStatuses?.find((s: any) => s.name === container.name);
                                    const isCtnRunning = status ? status.state === 'running' : container.running;
                                    return (
                                      <div key={container.name} className="flex items-center justify-between p-3 rounded-xl bg-gray-50 border border-gray-100 hover:border-blue-200 transition-all group/container">
                                        <div className="flex items-center gap-3">
                                          <div className={`w-2 h-2 rounded-full ${isCtnRunning ? 'bg-emerald-500' : 'bg-rose-500'} animate-pulse`}></div>
                                          <div className="flex flex-col">
                                            <span className="text-sm font-black text-gray-900">{container.name}</span>
                                            <span className="text-[10px] font-medium text-gray-500 truncate max-w-[200px]">{status?.image || container.image}</span>
                                          </div>
                                        </div>
                                        <div className="flex items-center gap-6">
                                          <div className="flex flex-col items-end">
                                            <span className="text-xs font-black text-gray-700">{(container.cpuUsage || 0).toFixed(1)}m</span>
                                            <span className="text-[9px] font-black text-gray-400 uppercase leading-none">CPU</span>
                                          </div>
                                          <div className="flex flex-col items-end">
                                            <span className="text-xs font-black text-gray-700">{(container.memoryUsage || 0).toFixed(0)}Mi</span>
                                            <span className="text-[9px] font-black text-gray-400 uppercase leading-none">Mem</span>
                                          </div>
                                          <div className="flex flex-col items-end min-w-[50px]">
                                            <span className={`text-xs font-black ${status?.restartCount > 5 ? 'text-rose-600' : 'text-gray-700'}`}>
                                              {status?.restartCount ?? container.restarts ?? 0}
                                            </span>
                                            <span className="text-[9px] font-black text-gray-400 uppercase leading-none">Restarts</span>
                                          </div>
                                        </div>
                                      </div>
                                    );
                                  })}
                                </div>
                              </div>
                            </div>
                          </div>
                        </td>
                      </tr>
                    )}
                  </React.Fragment>
                );
              })}
            </tbody>
          </table>
        </div>

        {/* 4. Functional Pagination */}
        <div className="px-8 py-5 bg-gray-50 border-t border-gray-100 flex items-center justify-between">
          <div className="text-xs font-black text-gray-400 uppercase tracking-tighter">
            Viewing <span className="text-gray-900">{(currentPage * ITEMS_PER_PAGE) + 1} - {Math.min((currentPage + 1) * ITEMS_PER_PAGE, processedPods.length)}</span> of <span className="text-gray-900">{processedPods.length}</span> resources
          </div>
          <div className="flex gap-2">
            <button
              disabled={currentPage === 0}
              onClick={() => setCurrentPage(prev => prev - 1)}
              className="px-5 py-2 bg-white border border-gray-200 rounded-xl text-xs font-black text-gray-700 hover:bg-gray-50 disabled:opacity-30 disabled:cursor-not-allowed transition-all shadow-sm"
            >
              Previous
            </button>
            <button
              disabled={currentPage === totalPages - 1 || totalPages === 0}
              onClick={() => setCurrentPage(prev => prev + 1)}
              className="px-5 py-2 bg-white border border-gray-200 rounded-xl text-xs font-black text-gray-700 hover:bg-gray-50 disabled:opacity-30 disabled:cursor-not-allowed transition-all shadow-sm"
            >
              Next
            </button>
          </div>
        </div>

        {processedPods.length === 0 && (
          <div className="py-24 text-center">
            <div className="w-20 h-20 bg-gray-50 rounded-full flex items-center justify-center mx-auto mb-6">
              <i className="fas fa-search text-gray-200 text-3xl"></i>
            </div>
            <h3 className="text-xl font-black text-gray-900 mb-2">No infrastructure matching your query</h3>
            <p className="text-gray-400 text-sm mb-8 font-medium">Try clearing filters or search to broaden your results.</p>
            <button
              onClick={resetFilters}
              className="px-8 py-3 bg-blue-600 text-white rounded-2xl font-black text-xs uppercase tracking-widest hover:bg-blue-700 transition-all shadow-lg shadow-blue-500/25"
            >
              Reset All Monitors
            </button>
          </div>
        )}
      </div>
    </div>
  );
};

export default EksPodsTab;
