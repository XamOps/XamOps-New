/**
 * GKE Containers Tab
 *
 * Lists all containers across pods with state, restart count, CPU/memory usage.
 */

import React, { useState, useMemo } from 'react';

const ITEMS_PER_PAGE = 10;

const DisconnectedPlaceholder: React.FC = () => (
  <div className="bg-white rounded-xl border border-amber-200 p-12 flex flex-col items-center text-center">
    <i className="fas fa-robot text-amber-300 text-5xl mb-4"></i>
    <h3 className="text-lg font-bold text-gray-700 mb-2">XamOps Agent Not Deployed</h3>
    <p className="text-gray-400 text-sm max-w-md">
      Deploy the XamOps agent on this cluster to see container metrics.
    </p>
  </div>
);

const stateChip = (state: string) => {
  const s = state?.toLowerCase();
  if (s === 'running')    return 'bg-green-100 text-green-700';
  if (s === 'waiting')    return 'bg-amber-100 text-amber-700';
  if (s === 'terminated') return 'bg-red-100 text-red-700';
  return 'bg-gray-100 text-gray-600';
};

const GkeContainersTab: React.FC<{ gkeData: any }> = ({ gkeData }) => {
  const { dashboard, allContainersWithMetrics, totalContainersCount, getContainersCountByState } = gkeData;
  const [currentPage, setCurrentPage] = useState(0);
  const [searchTerm, setSearchTerm] = useState('');
  const [namespaceFilter, setNamespaceFilter] = useState('all');
  const [stateFilter, setStateFilter] = useState('all');

  if (!dashboard?.connected || !dashboard?.containerMetrics) {
    return <DisconnectedPlaceholder />;
  }

  const namespaces: string[] = useMemo(() => {
    const ns = new Set<string>(allContainersWithMetrics.map((c: any) => c.namespace));
    return ['all', ...Array.from(ns).sort()];
  }, [allContainersWithMetrics]);

  const filtered = useMemo(() => {
    return allContainersWithMetrics.filter((c: any) => {
      const matchesSearch = !searchTerm ||
        c.name?.toLowerCase().includes(searchTerm.toLowerCase()) ||
        c.pod?.toLowerCase().includes(searchTerm.toLowerCase()) ||
        c.namespace?.toLowerCase().includes(searchTerm.toLowerCase());
      const matchesNs = namespaceFilter === 'all' || c.namespace === namespaceFilter;
      const matchesState = stateFilter === 'all' || c.state?.toLowerCase() === stateFilter.toLowerCase();
      return matchesSearch && matchesNs && matchesState;
    });
  }, [allContainersWithMetrics, searchTerm, namespaceFilter, stateFilter]);

  const totalPages = Math.ceil(filtered.length / ITEMS_PER_PAGE);
  const paginated = filtered.slice(currentPage * ITEMS_PER_PAGE, (currentPage + 1) * ITEMS_PER_PAGE);

  const handleFilterChange = (setter: (v: any) => void) => (val: any) => {
    setter(val);
    setCurrentPage(0);
  };

  return (
    <div className="space-y-6">
      {/* Status Overview Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {[
          { label: 'Running',    state: 'running',    icon: 'fa-play-circle', color: 'bg-green-50 text-green-600' },
          { label: 'Waiting',    state: 'waiting',    icon: 'fa-clock', color: 'bg-amber-50 text-amber-600' },
          { label: 'Terminated', state: 'terminated', icon: 'fa-stop-circle', color: 'bg-red-50 text-red-600' },
          { label: 'Total',      state: '',           icon: 'fa-layer-group', color: 'bg-blue-50 text-blue-600' },
        ].map(({ label, state, icon, color }) => (
          <div key={label} className="bg-white p-5 rounded-xl border border-gray-200 shadow-sm">
            <div className="flex items-center gap-3 mb-2">
              <div className={`w-10 h-10 rounded-lg flex items-center justify-center ${color}`}>
                <i className={`fas ${icon}`}></i>
              </div>
              <span className="text-xs font-semibold text-gray-500 uppercase tracking-wide">{label}</span>
            </div>
            <div className="text-2xl font-bold text-gray-900">
              {state ? getContainersCountByState(state) : totalContainersCount}
            </div>
          </div>
        ))}
      </div>

      {/* Filters */}
      <div className="bg-white p-4 rounded-xl border border-gray-200 shadow-sm flex flex-col md:flex-row gap-3 items-center">
        <div className="relative flex-1 md:w-72">
          <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400"></i>
          <input
            type="text"
            placeholder="Search containers, pods..."
            value={searchTerm}
            onChange={e => handleFilterChange(setSearchTerm)(e.target.value)}
            className="w-full pl-10 pr-4 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:ring-2 focus:ring-indigo-500 outline-none"
          />
        </div>
        <select
          value={namespaceFilter}
          onChange={e => handleFilterChange(setNamespaceFilter)(e.target.value)}
          className="px-4 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm outline-none"
        >
          {namespaces.map(ns => <option key={ns} value={ns}>{ns === 'all' ? 'All Namespaces' : ns}</option>)}
        </select>
        <select
          value={stateFilter}
          onChange={e => handleFilterChange(setStateFilter)(e.target.value)}
          className="px-4 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm outline-none"
        >
          <option value="all">All States</option>
          <option value="running">Running</option>
          <option value="waiting">Waiting</option>
          <option value="terminated">Terminated</option>
        </select>
        <span className="text-xs text-gray-400 ml-auto whitespace-nowrap">{filtered.length} containers</span>
      </div>

      {/* Containers Table */}
      <div className="bg-white rounded-xl border border-gray-200 shadow-sm overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="bg-gray-50">
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Container</th>
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Pod</th>
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Namespace</th>
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">State</th>
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider text-center">Restarts</th>
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider text-right">CPU (m)</th>
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider text-right">Memory (MiB)</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {paginated.map((c: any, i: number) => (
                <tr key={i} className="hover:bg-gray-50 transition-colors">
                  <td className="px-6 py-4">
                    <div className="flex items-center gap-2">
                      <div className={`w-2 h-2 rounded-full flex-shrink-0 ${c.ready ? 'bg-green-500' : 'bg-gray-300'}`}></div>
                      <span className="font-bold text-gray-900 text-sm">{c.name}</span>
                    </div>
                  </td>
                  <td className="px-6 py-4">
                    <span className="text-xs text-gray-500 font-mono truncate block max-w-[160px]">{c.pod}</span>
                  </td>
                  <td className="px-6 py-4">
                    <span className="text-sm text-gray-600 bg-gray-100 px-2 py-1 rounded">{c.namespace}</span>
                  </td>
                  <td className="px-6 py-4">
                    <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-bold ${stateChip(c.state)}`}>
                      <div className="w-1.5 h-1.5 rounded-full bg-current"></div>
                      {c.state || '—'}
                    </span>
                  </td>
                  <td className="px-6 py-4 text-center">
                    <span className={`text-sm font-bold ${c.restartCount > 5 ? 'text-red-600' : c.restartCount > 0 ? 'text-amber-600' : 'text-gray-700'}`}>
                      {c.restartCount}
                    </span>
                  </td>
                  <td className="px-6 py-4 text-right text-sm text-gray-700">{(c.cpuUsage * 1000).toFixed(1)}</td>
                  <td className="px-6 py-4 text-right text-sm text-gray-700">{(c.memoryUsage / 1048576).toFixed(1)}</td>
                </tr>
              ))}
              {paginated.length === 0 && (
                <tr>
                  <td colSpan={7} className="px-6 py-12 text-center text-gray-400">
                    <i className="fas fa-search text-3xl mb-3 block"></i>
                    No containers found matching your filters
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        {totalPages > 1 && (
          <div className="px-6 py-4 bg-gray-50 border-t border-gray-200 flex items-center justify-between">
            <span className="text-sm text-gray-500">
              {currentPage * ITEMS_PER_PAGE + 1}–{Math.min((currentPage + 1) * ITEMS_PER_PAGE, filtered.length)} of {filtered.length}
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

export default GkeContainersTab;
