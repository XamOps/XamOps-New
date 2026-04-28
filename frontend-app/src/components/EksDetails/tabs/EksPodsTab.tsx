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
  // Primary: new k8s-resources collector; fallback: old workloadHealth
  const pods = dashboard?.k8sPods?.length
    ? dashboard.k8sPods
    : (dashboard?.workloadHealth?.pods || []);

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
          <div key={stat.label} className="bg-white px-5 py-4 rounded-lg border border-[#d0d7de]">
            <div className="flex items-center justify-between mb-1">
              <span className="text-[11px] font-medium text-[#8b949e] uppercase tracking-wider">{stat.label}</span>
              <div className={`w-7 h-7 rounded-md flex items-center justify-center bg-${stat.color}-50 text-${stat.color}-600`}>
                <i className={`fas ${stat.icon} text-xs`}></i>
              </div>
            </div>
            <div className="text-[26px] font-semibold leading-none text-[#1a1f2e]">{stat.count}</div>
          </div>
        ))}
      </div>

      {/* Resource Requests vs Limits Overview Charts */}
      {podResourceMetrics && (
        <div className="bg-white rounded-lg border border-[#d0d7de] p-6">
          <h3 className="text-[13px] font-semibold text-[#1a1f2e] mb-6 flex items-center gap-2">
            <div className="w-7 h-7 rounded-md bg-blue-50 flex items-center justify-center">
              <i className="fas fa-chart-bar text-[#2f4bde] text-xs"></i>
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
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mt-6 pt-6 border-t border-[#e8ecf0]">
            <div className="text-center">
              <div className="text-[11px] text-[#8b949e] mb-1">Pods Without Requests</div>
              <div className="text-[22px] font-semibold text-[#9a6700]">{podResourceMetrics.podsWithoutRequests}</div>
            </div>
            <div className="text-center">
              <div className="text-[11px] text-[#8b949e] mb-1">Pods Without Limits</div>
              <div className="text-[22px] font-semibold text-[#cf222e]">{podResourceMetrics.podsWithoutLimits}</div>
            </div>
            <div className="text-center">
              <div className="text-[11px] text-[#8b949e] mb-1">Request Utilization</div>
              <div className="text-[22px] font-semibold text-[#2f4bde]">
                {podResourceMetrics.totalCpuRequests > 0
                  ? ((podResourceMetrics.totalCpuUsage / podResourceMetrics.totalCpuRequests) * 100).toFixed(1)
                  : '0.0'}%
              </div>
            </div>
            <div className="text-center">
              <div className="text-[11px] text-[#8b949e] mb-1">Limit Utilization</div>
              <div className="text-[22px] font-semibold text-[#9a6700]">
                {podResourceMetrics.totalCpuLimits > 0
                  ? ((podResourceMetrics.totalCpuUsage / podResourceMetrics.totalCpuLimits) * 100).toFixed(1)
                  : '0.0'}%
              </div>
            </div>
          </div>
        </div>
      )}

      {/* 2. Sticky Filtering controls */}
      <div className="sticky top-0 z-20 bg-[#f6f8fa]/90 backdrop-blur-md py-3">
        <div className="bg-white p-2 rounded-lg border border-[#d0d7de] flex flex-col lg:flex-row gap-3 items-center">
          <div className="flex bg-[#f6f8fa] p-1 rounded-md w-full lg:w-auto shrink-0 border border-[#e8ecf0]">
            {['all', 'Running', 'Pending', 'Failed'].map((p) => (
              <button
                key={p}
                onClick={() => setPhaseFilter(p as any)}
                className={`flex-1 lg:flex-none px-4 py-1.5 rounded text-[12px] font-medium transition-all ${phaseFilter === p
                  ? 'bg-white text-[#2f4bde] border border-[#d0d7de]'
                  : 'text-[#57606a] hover:text-[#1a1f2e]'
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
      <div className="bg-white rounded-lg border border-[#d0d7de] overflow-hidden">
        <div className="overflow-x-auto no-scrollbar">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="bg-[#f6f8fa] border-b border-[#e8ecf0]">
                <th className="px-6 py-3 text-[11px] font-medium uppercase tracking-wider text-[#8b949e] w-20">Status</th>
                <th
                  className="px-6 py-3 text-[11px] font-medium uppercase tracking-wider text-[#8b949e] cursor-pointer hover:text-[#2f4bde] transition-colors"
                  onClick={() => setSortBy('name')}
                >
                  Pod Name {sortBy === 'name' && <i className={`fas fa-caret-${sortOrder === 'asc' ? 'up' : 'down'} ml-1`}></i>}
                </th>
                <th className="px-6 py-3 text-[11px] font-medium uppercase tracking-wider text-[#8b949e]">Node</th>
                <th
                  className="px-6 py-3 text-[11px] font-medium uppercase tracking-wider text-[#8b949e] cursor-pointer hover:text-[#2f4bde] transition-colors"
                  onClick={() => setSortBy('restarts')}
                >
                  Restarts {sortBy === 'restarts' && <i className={`fas fa-caret-${sortOrder === 'asc' ? 'up' : 'down'} ml-1`}></i>}
                </th>
                <th
                  className="px-6 py-3 text-[11px] font-medium uppercase tracking-wider text-[#8b949e] cursor-pointer hover:text-[#2f4bde] transition-colors"
                  onClick={() => setSortBy('cpuUsage')}
                >
                  CPU Usage {sortBy === 'cpuUsage' && <i className={`fas fa-caret-${sortOrder === 'asc' ? 'up' : 'down'} ml-1`}></i>}
                </th>
                <th
                  className="px-6 py-3 text-[11px] font-medium uppercase tracking-wider text-[#8b949e] cursor-pointer hover:text-[#2f4bde] transition-colors"
                  onClick={() => setSortBy('memoryUsage')}
                >
                  Memory {sortBy === 'memoryUsage' && <i className={`fas fa-caret-${sortOrder === 'asc' ? 'up' : 'down'} ml-1`}></i>}
                </th>
                <th
                  className="px-6 py-3 text-[11px] font-medium uppercase tracking-wider text-[#8b949e] cursor-pointer hover:text-[#2f4bde] transition-colors"
                  onClick={() => setSortBy('age')}
                >
                  Age {sortBy === 'age' && <i className={`fas fa-caret-${sortOrder === 'asc' ? 'up' : 'down'} ml-1`}></i>}
                </th>
                <th className="px-6 py-3 text-[11px] font-medium uppercase tracking-wider text-[#8b949e] w-10"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[#e8ecf0]">
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
                      className={`group hover:bg-[#f6f8fa] transition-all cursor-pointer ${isExpanded ? 'bg-[#f6f8fa]' : ''}`}
                      onClick={() => setExpandedPod(isExpanded ? null : pod.name)}
                    >
                      <td className="px-6 py-3.5">
                        <div className={`rounded-full flex items-center justify-center w-7 h-7 ${isRunning ? 'bg-[#dafbe1] text-[#1a7f37]' :
                          isPending ? 'bg-[#fff8c5] text-[#9a6700]' : 'bg-[#ffebe9] text-[#cf222e]'
                          }`}>
                          <i className={`fas ${isRunning ? 'fa-check-circle' : isPending ? 'fa-clock' : 'fa-times-circle'} text-xs`}></i>
                        </div>
                      </td>
                      <td className="px-6 py-3.5">
                        <div className="flex flex-col max-w-[280px]">
                          <span className="text-[13px] font-semibold text-[#1a1f2e] truncate" title={pod.name}>{pod.name}</span>
                          <span className="text-[11px] text-[#8b949e]">{pod.namespace}</span>
                        </div>
                      </td>
                      <td className="px-6 py-3.5">
                        <div className="flex items-center gap-2 text-[13px] text-[#57606a]">
                          <i className="fas fa-microchip text-[#8b949e] text-xs"></i>
                          <span className="truncate max-w-[120px]">{pod.nodeName || pod.node || 'N/A'}</span>
                        </div>
                      </td>
                      <td className="px-6 py-3.5 text-center">
                        <span className={`text-[13px] font-semibold ${pod.restarts > 5 ? 'text-[#cf222e]' : 'text-[#1a1f2e]'}`}>
                          {pod.restarts || 0}
                        </span>
                      </td>
                      <td className="px-6 py-3.5">
                        <div className="flex flex-col gap-1.5 w-28">
                          <div className="flex justify-between items-center text-[11px]">
                            <span className="text-[#1a1f2e] font-medium">{cpuUsage.toFixed(1)}m</span>
                            <span className="text-[#8b949e]">{cpuLimit > 0 ? `${((cpuUsage / cpuLimit) * 100).toFixed(0)}%` : ''}</span>
                          </div>
                          <div className="h-1.5 w-full bg-[#e8ecf0] rounded-full overflow-hidden">
                            <div
                              className="h-full rounded-full transition-all duration-700"
                              style={{ width: `${cpuLimit > 0 ? Math.min(100, (cpuUsage / cpuLimit) * 100) : (cpuUsage > 0 ? 30 : 5)}%`, background: cpuUsage > 800 ? '#cf222e' : '#2f4bde' }}
                            ></div>
                          </div>
                        </div>
                      </td>
                      <td className="px-6 py-3.5">
                        <div className="flex flex-col gap-1.5 w-28">
                          <div className="flex justify-between items-center text-[11px]">
                            <span className="text-[#1a1f2e] font-medium">{memUsage.toFixed(0)}Mi</span>
                            <span className="text-[#8b949e]">{memLimit > 0 ? `${((memUsage / memLimit) * 100).toFixed(0)}%` : ''}</span>
                          </div>
                          <div className="h-1.5 w-full bg-[#e8ecf0] rounded-full overflow-hidden">
                            <div
                              className="h-full rounded-full transition-all duration-700"
                              style={{ width: `${memLimit > 0 ? Math.min(100, (memUsage / memLimit) * 100) : (memUsage > 0 ? 30 : 5)}%`, background: memUsage > 1024 ? '#9a6700' : '#2f4bde' }}
                            ></div>
                          </div>
                        </div>
                      </td>
                      <td className="px-6 py-3.5">
                        <span className="text-[11px] text-[#57606a] bg-[#f6f8fa] border border-[#e8ecf0] px-2 py-0.5 rounded">{pod.age || 'N/A'}</span>
                      </td>
                      <td className="px-6 py-3.5 text-right">
                        <i className={`fas fa-chevron-right text-[#8b949e] transition-transform duration-300 text-xs ${isExpanded ? 'rotate-90 text-[#2f4bde]' : ''}`}></i>
                      </td>
                    </tr>

                    {/* Expansion Drawer */}
                    {isExpanded && (
                      <tr>
                        <td colSpan={8} className="p-0 border-none">
                          <div className="px-8 py-5 bg-[#f6f8fa] border-y border-[#e8ecf0]">
                            <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
                              {/* Metadata Card */}
                              <div className="bg-white p-4 rounded-lg border border-[#d0d7de] space-y-3">
                                <h4 className="text-[11px] font-medium text-[#2f4bde] uppercase tracking-wider flex items-center gap-2">
                                  <i className="fas fa-fingerprint"></i> Pod Identification
                                </h4>
                                <div className="space-y-2.5">
                                  <div className="flex justify-between">
                                    <span className="text-[11px] text-[#8b949e]">IP Address</span>
                                    <span className="text-[11px] font-mono text-[#1a1f2e]">{pod.podIp || 'N/A'}</span>
                                  </div>
                                  <div className="flex justify-between">
                                    <span className="text-[11px] text-[#8b949e]">Owner Type</span>
                                    <span className="text-[11px] text-[#1a1f2e]">{pod.ownerKind || 'N/A'}</span>
                                  </div>
                                  <div className="flex justify-between">
                                    <span className="text-[11px] text-[#8b949e]">Owner Name</span>
                                    <span className="text-[11px] text-[#2f4bde] truncate max-w-[140px]">{pod.ownerName || 'N/A'}</span>
                                  </div>
                                  <div className="flex justify-between">
                                    <span className="text-[11px] text-[#8b949e]">QoS Class</span>
                                    <span className="px-1.5 py-0.5 bg-[#f6f8fa] border border-[#e8ecf0] rounded text-[11px] text-[#57606a]">{pod.qosClass || 'Guaranteed'}</span>
                                  </div>
                                </div>
                              </div>

                              {/* Resource Details Card */}
                              <div className="lg:col-span-2 bg-white p-4 rounded-lg border border-[#d0d7de]">
                                <h4 className="text-[11px] font-medium text-[#2f4bde] uppercase tracking-wider mb-3 flex items-center gap-2">
                                  <i className="fas fa-chart-line"></i> Container Utilization ({pod.containers?.length || 0})
                                </h4>
                                <div className="space-y-1.5 max-h-[240px] overflow-y-auto pr-2 no-scrollbar">
                                  {(pod.containers || []).map((container: any) => {
                                    const status = (pod.containers || pod.containerStatuses)?.find((s: any) => s.name === container.name);
                                    const isCtnRunning = status ? status.state === 'running' : container.running;
                                    return (
                                      <div key={container.name} className="flex items-center justify-between p-2.5 rounded-md bg-[#f6f8fa] border border-[#e8ecf0] hover:border-[#d0d7de] transition-all">
                                        <div className="flex items-center gap-2.5">
                                          <div className={`w-2 h-2 rounded-full ${isCtnRunning ? 'bg-[#1a7f37]' : 'bg-[#cf222e]'}`}></div>
                                          <div className="flex flex-col">
                                            <span className="text-[12px] font-medium text-[#1a1f2e]">{container.name}</span>
                                            <span className="text-[11px] text-[#8b949e] truncate max-w-[200px]">{status?.image || container.image}</span>
                                          </div>
                                        </div>
                                        <div className="flex items-center gap-5">
                                          <div className="flex flex-col items-end">
                                            <span className="text-[11px] font-medium text-[#57606a]">{(container.cpuUsage || 0).toFixed(1)}m</span>
                                            <span className="text-[10px] text-[#8b949e] uppercase leading-none">CPU</span>
                                          </div>
                                          <div className="flex flex-col items-end">
                                            <span className="text-[11px] font-medium text-[#57606a]">{(container.memoryUsage || 0).toFixed(0)}Mi</span>
                                            <span className="text-[10px] text-[#8b949e] uppercase leading-none">Mem</span>
                                          </div>
                                          <div className="flex flex-col items-end min-w-[50px]">
                                            <span className={`text-[11px] font-medium ${status?.restartCount > 5 ? 'text-[#cf222e]' : 'text-[#57606a]'}`}>
                                              {status?.restartCount ?? container.restarts ?? 0}
                                            </span>
                                            <span className="text-[10px] text-[#8b949e] uppercase leading-none">Restarts</span>
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
        <div className="px-6 py-4 bg-[#f6f8fa] border-t border-[#e8ecf0] flex items-center justify-between">
          <div className="text-[11px] text-[#8b949e]">
            Viewing <span className="text-[#1a1f2e] font-medium">{(currentPage * ITEMS_PER_PAGE) + 1} – {Math.min((currentPage + 1) * ITEMS_PER_PAGE, processedPods.length)}</span> of <span className="text-[#1a1f2e] font-medium">{processedPods.length}</span> pods
          </div>
          <div className="flex gap-2">
            <button
              disabled={currentPage === 0}
              onClick={() => setCurrentPage(prev => prev - 1)}
              className="px-4 py-1.5 bg-white border border-[#d0d7de] rounded-md text-[12px] font-medium text-[#57606a] hover:bg-[#f6f8fa] disabled:opacity-40 disabled:cursor-not-allowed transition-all"
            >
              Previous
            </button>
            <button
              disabled={currentPage === totalPages - 1 || totalPages === 0}
              onClick={() => setCurrentPage(prev => prev + 1)}
              className="px-4 py-1.5 bg-white border border-[#d0d7de] rounded-md text-[12px] font-medium text-[#57606a] hover:bg-[#f6f8fa] disabled:opacity-40 disabled:cursor-not-allowed transition-all"
            >
              Next
            </button>
          </div>
        </div>

        {processedPods.length === 0 && (
          <div className="py-16 text-center">
            <div className="w-16 h-16 bg-[#f6f8fa] border border-[#e8ecf0] rounded-lg flex items-center justify-center mx-auto mb-4">
              <i className="fas fa-search text-[#8b949e] text-xl"></i>
            </div>
            <h3 className="text-[14px] font-semibold text-[#1a1f2e] mb-1">No pods matching your filters</h3>
            <p className="text-[13px] text-[#57606a] mb-5">Try clearing filters or broadening your search.</p>
            <button
              onClick={resetFilters}
              className="px-5 py-2 bg-[#2f4bde] text-white rounded-md font-medium text-[12px] hover:bg-[#2440c4] transition-all"
            >
              Reset Filters
            </button>
          </div>
        )}
      </div>
    </div>
  );
};

export default EksPodsTab;
