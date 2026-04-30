/**
 * GKE Workloads Tab
 *
 * Displays deployments, statefulsets, and daemonsets with pagination and filtering.
 * Adapted from EksWorkloadsTab.tsx for GKE.
 */

import React, { useState, useMemo } from 'react';

const ITEMS_PER_PAGE = 5;

const GkeWorkloadsTab: React.FC<{ gkeData: any }> = ({ gkeData }) => {
  const { dashboard } = gkeData;
  const [selectedType, setSelectedType] = useState<'deployments' | 'statefulsets' | 'daemonsets'>('deployments');
  const [currentPage, setCurrentPage] = useState(0);
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState<'all' | 'healthy' | 'degraded'>('all');

  if (!dashboard?.connected || !dashboard?.workloadHealth) {
    return (
      <div className="bg-white rounded-xl border border-amber-200 p-12 flex flex-col items-center text-center">
        <i className="fas fa-robot text-amber-300 text-5xl mb-4"></i>
        <h3 className="text-lg font-bold text-gray-700 mb-2">XamOps Agent Not Deployed</h3>
        <p className="text-gray-400 text-sm max-w-md">
          Deploy the XamOps agent on this cluster to see workload data. Click the <strong>?</strong> button on the cluster list page for the full setup guide.
        </p>
      </div>
    );
  }

  const deployments = dashboard.workloadHealth?.deployments || [];
  const statefulSets = dashboard.workloadHealth?.statefulsets || [];
  const daemonSets = dashboard.workloadHealth?.daemonsets || [];

  const filteredWorkloads = useMemo(() => {
    let workloads: any[] = [];
    switch (selectedType) {
      case 'statefulsets': workloads = statefulSets; break;
      case 'daemonsets': workloads = daemonSets; break;
      default: workloads = deployments;
    }

    return workloads.filter((w: any) => {
      const matchesSearch = w.name?.toLowerCase().includes(searchTerm.toLowerCase()) ||
        w.namespace?.toLowerCase().includes(searchTerm.toLowerCase());

      const ready = w.readyReplicas ?? w.availableReplicas ?? 0;
      const desired = w.replicas ?? 0;
      const isHealthy = desired > 0 ? ready >= desired : true;

      const matchesStatus = statusFilter === 'all' ||
        (statusFilter === 'healthy' && isHealthy) ||
        (statusFilter === 'degraded' && !isHealthy);

      return matchesSearch && matchesStatus;
    });
  }, [selectedType, deployments, statefulSets, daemonSets, searchTerm, statusFilter]);

  const paginatedWorkloads = filteredWorkloads.slice(currentPage * ITEMS_PER_PAGE, (currentPage + 1) * ITEMS_PER_PAGE);
  const totalPages = Math.ceil(filteredWorkloads.length / ITEMS_PER_PAGE);

  const handleTypeChange = (type: 'deployments' | 'statefulsets' | 'daemonsets') => {
    setSelectedType(type);
    setCurrentPage(0);
  };

  return (
    <div className="space-y-6">
      {/* Overview Stats */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {[
          { label: 'Deployments', count: deployments.length, icon: 'fa-layer-group', color: 'bg-blue-50 text-blue-600' },
          { label: 'StatefulSets', count: statefulSets.length, icon: 'fa-database', color: 'bg-indigo-50 text-indigo-600' },
          { label: 'DaemonSets', count: daemonSets.length, icon: 'fa-network-wired', color: 'bg-teal-50 text-teal-600' },
          { label: 'Health Score', count: `${Math.round(dashboard.workloadHealth?.clusterHealthScore ?? 0)}%`, icon: 'fa-heartbeat', color: 'bg-purple-50 text-purple-600' },
        ].map(({ label, count, icon, color }) => (
          <div key={label} className="bg-white p-5 rounded-xl border border-gray-200 shadow-sm">
            <div className="flex items-center gap-3 mb-2">
              <div className={`w-10 h-10 rounded-lg flex items-center justify-center ${color}`}>
                <i className={`fas ${icon}`}></i>
              </div>
              <span className="text-xs font-semibold text-gray-500 uppercase tracking-wide">{label}</span>
            </div>
            <div className="text-2xl font-bold text-gray-900">{count}</div>
          </div>
        ))}
      </div>

      {/* Filters */}
      <div className="bg-white p-4 rounded-xl border border-gray-200 shadow-sm flex flex-col md:flex-row gap-4 items-center justify-between">
        <div className="flex items-center gap-2 bg-gray-100 p-1 rounded-lg">
          {(['deployments', 'statefulsets', 'daemonsets'] as const).map(type => (
            <button
              key={type}
              onClick={() => handleTypeChange(type)}
              className={`px-4 py-2 rounded-md text-sm font-medium transition-all capitalize ${
                selectedType === type ? 'bg-white shadow-sm text-indigo-600' : 'text-gray-600 hover:text-gray-900'
              }`}
            >
              {type.charAt(0).toUpperCase() + type.slice(1)}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-3 w-full md:w-auto">
          <div className="relative flex-1 md:w-64">
            <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400"></i>
            <input
              type="text"
              placeholder="Search workloads..."
              value={searchTerm}
              onChange={e => { setSearchTerm(e.target.value); setCurrentPage(0); }}
              className="w-full pl-10 pr-4 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:ring-2 focus:ring-indigo-500 outline-none"
            />
          </div>
          <select
            value={statusFilter}
            onChange={(e: any) => { setStatusFilter(e.target.value); setCurrentPage(0); }}
            className="px-4 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm outline-none"
          >
            <option value="all">All States</option>
            <option value="healthy">Healthy</option>
            <option value="degraded">Degraded</option>
          </select>
        </div>
      </div>

      {/* Table */}
      <div className="bg-white rounded-xl border border-gray-200 shadow-sm overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="bg-gray-50">
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Name</th>
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Namespace</th>
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider text-center">Replicas</th>
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {paginatedWorkloads.map((w: any, i: number) => {
                const ready = w.readyReplicas ?? w.availableReplicas ?? 0;
                const desired = w.replicas ?? 0;
                const isHealthy = desired > 0 ? ready >= desired : true;
                const icon = selectedType === 'statefulsets' ? 'fa-database' : selectedType === 'daemonsets' ? 'fa-network-wired' : 'fa-layer-group';

                return (
                  <tr key={i} className="hover:bg-gray-50 transition-colors">
                    <td className="px-6 py-4">
                      <div className="flex items-center gap-3">
                        <div className={`w-8 h-8 rounded flex items-center justify-center flex-shrink-0 ${isHealthy ? 'bg-green-50 text-green-600' : 'bg-red-50 text-red-600'}`}>
                          <i className={`fas ${icon} text-sm`}></i>
                        </div>
                        <span className="font-bold text-gray-900">{w.name}</span>
                      </div>
                    </td>
                    <td className="px-6 py-4">
                      <span className="text-sm text-gray-600 bg-gray-100 px-2 py-1 rounded">{w.namespace}</span>
                    </td>
                    <td className="px-6 py-4">
                      <div className="flex flex-col items-center">
                        <span className="text-sm font-bold text-gray-900">{ready} / {desired}</span>
                        <div className="w-16 h-1.5 bg-gray-100 rounded-full mt-1.5 overflow-hidden">
                          <div
                            className={`h-full rounded-full ${isHealthy ? 'bg-green-500' : 'bg-red-500'}`}
                            style={{ width: `${desired > 0 ? (ready / desired) * 100 : 0}%` }}
                          />
                        </div>
                      </div>
                    </td>
                    <td className="px-6 py-4">
                      <span className={`inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-bold ${isHealthy ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'}`}>
                        <div className={`w-2 h-2 rounded-full ${isHealthy ? 'bg-green-500' : 'bg-red-500'}`}></div>
                        {isHealthy ? 'Healthy' : 'Degraded'}
                      </span>
                    </td>
                  </tr>
                );
              })}
              {paginatedWorkloads.length === 0 && (
                <tr>
                  <td colSpan={4} className="px-6 py-12 text-center text-gray-400">
                    <i className="fas fa-search text-3xl mb-3"></i>
                    <p>No {selectedType} found matching your filters</p>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        {totalPages > 1 && (
          <div className="px-6 py-4 bg-gray-50 border-t border-gray-200 flex items-center justify-between">
            <span className="text-sm text-gray-500">
              {currentPage * ITEMS_PER_PAGE + 1}–{Math.min((currentPage + 1) * ITEMS_PER_PAGE, filteredWorkloads.length)} of {filteredWorkloads.length}
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

export default GkeWorkloadsTab;
