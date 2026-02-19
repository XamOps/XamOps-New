/**
 * GKE Pods Tab
 *
 * Pod monitoring table with phase filtering, search, and namespace filter.
 * Adapted from EksPodsTab.tsx for GKE.
 */

import React, { useState, useMemo } from 'react';
import { GkePodInfo } from '../../../types/gkeTypes';

const ITEMS_PER_PAGE = 10;

const GkePodsTab: React.FC<{ gkeData: any }> = ({ gkeData }) => {
  const { dashboard, getPodsCountByPhase } = gkeData;

  const [searchQuery, setSearchQuery] = useState('');
  const [selectedNamespace, setSelectedNamespace] = useState('all');
  const [phaseFilter, setPhaseFilter] = useState<'all' | 'Running' | 'Pending' | 'Failed'>('all');
  const [currentPage, setCurrentPage] = useState(0);

  if (!dashboard?.connected || !dashboard?.workloadHealth) {
    return (
      <div className="bg-white rounded-xl border border-amber-200 p-12 flex flex-col items-center text-center">
        <i className="fas fa-plug text-amber-300 text-5xl mb-4"></i>
        <h3 className="text-lg font-bold text-gray-700 mb-2">Monitoring Not Connected</h3>
        <p className="text-gray-400 text-sm max-w-md">
          Enable monitoring on this cluster to see pod metrics and status.
        </p>
      </div>
    );
  }

  const pods: GkePodInfo[] = dashboard.workloadHealth?.pods ?? [];

  const namespaces = useMemo(() => {
    const ns = new Set<string>();
    pods.forEach(p => p.namespace && ns.add(p.namespace));
    return ['all', ...Array.from(ns).sort()];
  }, [pods]);

  const processedPods = useMemo(() => {
    let result = [...pods];
    if (phaseFilter !== 'all') result = result.filter(p => p.phase === phaseFilter);
    if (selectedNamespace !== 'all') result = result.filter(p => p.namespace === selectedNamespace);
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      result = result.filter(p =>
        p.name?.toLowerCase().includes(q) ||
        p.namespace?.toLowerCase().includes(q) ||
        p.nodeName?.toLowerCase().includes(q)
      );
    }
    return result;
  }, [pods, phaseFilter, selectedNamespace, searchQuery]);

  const paginatedPods = processedPods.slice(currentPage * ITEMS_PER_PAGE, (currentPage + 1) * ITEMS_PER_PAGE);
  const totalPages = Math.ceil(processedPods.length / ITEMS_PER_PAGE);

  const getPhaseChip = (phase: string) => {
    switch (phase) {
      case 'Running': return 'bg-green-100 text-green-700';
      case 'Pending': return 'bg-amber-100 text-amber-700';
      case 'Failed': return 'bg-red-100 text-red-700';
      case 'Succeeded': return 'bg-blue-100 text-blue-700';
      default: return 'bg-gray-100 text-gray-600';
    }
  };

  const formatMem = (bytes: number) => {
    if (!bytes) return '—';
    if (bytes < 1048576) return `${(bytes / 1024).toFixed(0)}Ki`;
    return `${(bytes / 1048576).toFixed(0)}Mi`;
  };

  const formatCpu = (cores: number) => {
    if (!cores) return '—';
    if (cores < 1) return `${(cores * 1000).toFixed(0)}m`;
    return `${cores.toFixed(2)}`;
  };

  return (
    <div className="space-y-6">
      {/* Status summary */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {[
          { label: 'Running', phase: 'Running', color: 'bg-green-50 text-green-600' },
          { label: 'Pending', phase: 'Pending', color: 'bg-amber-50 text-amber-600' },
          { label: 'Failed', phase: 'Failed', color: 'bg-red-50 text-red-600' },
          { label: 'Total', phase: 'all', color: 'bg-indigo-50 text-indigo-600' },
        ].map(({ label, phase, color }) => (
          <div key={label} className="bg-white p-5 rounded-xl border border-gray-200 shadow-sm">
            <div className="flex items-center gap-3 mb-2">
              <div className={`w-10 h-10 rounded-lg flex items-center justify-center ${color}`}>
                <i className="fas fa-box"></i>
              </div>
              <span className="text-xs font-semibold text-gray-500 uppercase tracking-wide">{label}</span>
            </div>
            <div className="text-2xl font-bold text-gray-900">
              {phase === 'all' ? pods.length : getPodsCountByPhase(phase)}
            </div>
          </div>
        ))}
      </div>

      {/* Filters */}
      <div className="bg-white p-4 rounded-xl border border-gray-200 shadow-sm flex flex-col md:flex-row gap-4 items-center justify-between flex-wrap">
        <div className="flex items-center gap-2 bg-gray-100 p-1 rounded-lg">
          {(['all', 'Running', 'Pending', 'Failed'] as const).map(p => (
            <button key={p} onClick={() => { setPhaseFilter(p); setCurrentPage(0); }}
              className={`px-3 py-1.5 rounded-md text-xs font-semibold transition-all ${phaseFilter === p ? 'bg-white shadow-sm text-indigo-600' : 'text-gray-600 hover:text-gray-900'}`}>
              {p === 'all' ? 'All' : p}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-3 w-full md:w-auto">
          <div className="relative flex-1 md:w-56">
            <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400"></i>
            <input
              type="text"
              placeholder="Search pods..."
              value={searchQuery}
              onChange={e => { setSearchQuery(e.target.value); setCurrentPage(0); }}
              className="w-full pl-10 pr-4 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:ring-2 focus:ring-indigo-500 outline-none"
            />
          </div>
          <select
            value={selectedNamespace}
            onChange={e => { setSelectedNamespace(e.target.value); setCurrentPage(0); }}
            className="px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm outline-none"
          >
            {namespaces.map(ns => (
              <option key={ns} value={ns}>{ns === 'all' ? 'All Namespaces' : ns}</option>
            ))}
          </select>
        </div>
      </div>

      {/* Table */}
      <div className="bg-white rounded-xl border border-gray-200 shadow-sm overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="bg-gray-50">
                <th className="px-5 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Pod</th>
                <th className="px-5 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Namespace</th>
                <th className="px-5 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Phase</th>
                <th className="px-5 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Node</th>
                <th className="px-5 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider text-center">Restarts</th>
                <th className="px-5 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">CPU</th>
                <th className="px-5 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Memory</th>
                <th className="px-5 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Age</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {paginatedPods.map((pod, i) => (
                <tr key={i} className="hover:bg-gray-50 transition-colors">
                  <td className="px-5 py-3">
                    <span className="font-semibold text-gray-900 text-sm truncate max-w-[200px] block">{pod.name}</span>
                  </td>
                  <td className="px-5 py-3">
                    <span className="text-xs text-gray-600 bg-gray-100 px-2 py-1 rounded">{pod.namespace}</span>
                  </td>
                  <td className="px-5 py-3">
                    <span className={`inline-flex items-center gap-1 px-2 py-1 rounded-full text-xs font-bold ${getPhaseChip(pod.phase)}`}>
                      {pod.phase}
                    </span>
                  </td>
                  <td className="px-5 py-3">
                    <span className="text-xs text-gray-500 truncate max-w-[120px] block">{pod.nodeName || '—'}</span>
                  </td>
                  <td className="px-5 py-3 text-center">
                    <span className={`text-sm font-bold ${pod.restartCount > 5 ? 'text-red-600' : pod.restartCount > 0 ? 'text-amber-600' : 'text-gray-500'}`}>
                      {pod.restartCount}
                    </span>
                  </td>
                  <td className="px-5 py-3 text-sm text-gray-600">
                    {formatCpu(pod.cpuUsage)}
                  </td>
                  <td className="px-5 py-3 text-sm text-gray-600">
                    {formatMem(pod.memoryUsage)}
                  </td>
                  <td className="px-5 py-3 text-xs text-gray-500">{pod.age || '—'}</td>
                </tr>
              ))}
              {paginatedPods.length === 0 && (
                <tr>
                  <td colSpan={8} className="px-6 py-12 text-center text-gray-400">
                    <i className="fas fa-search text-3xl mb-3"></i>
                    <p>No pods found matching your filters</p>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        {totalPages > 1 && (
          <div className="px-6 py-4 bg-gray-50 border-t border-gray-200 flex items-center justify-between">
            <span className="text-sm text-gray-500">
              {currentPage * ITEMS_PER_PAGE + 1}–{Math.min((currentPage + 1) * ITEMS_PER_PAGE, processedPods.length)} of {processedPods.length}
            </span>
            <div className="flex gap-2">
              <button disabled={currentPage === 0} onClick={() => setCurrentPage(p => p - 1)}
                className="px-4 py-2 bg-white border border-gray-300 rounded-lg text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50">
                Previous
              </button>
              <button disabled={currentPage === totalPages - 1} onClick={() => setCurrentPage(p => p + 1)}
                className="px-4 py-2 bg-white border border-gray-300 rounded-lg text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50">
                Next
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default GkePodsTab;
