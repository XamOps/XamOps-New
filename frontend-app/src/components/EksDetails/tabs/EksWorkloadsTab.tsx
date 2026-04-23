/**
 * EKS Workloads Tab
 * 
 * Professional workload monitoring with deployments, statefulsets, and daemonsets.
 * Features: Pagination, filtering, and detailed resource metrics.
 */

import React, { useState, useMemo } from 'react';

const ITEMS_PER_PAGE = 5;

const EksWorkloadsTab: React.FC<{ eksData: any }> = ({ eksData }) => {
  const { dashboard } = eksData;
  const [selectedWorkloadType, setSelectedWorkloadType] = useState<'deployments' | 'statefulsets' | 'daemonsets'>('deployments');
  const [currentPage, setCurrentPage] = useState(0);
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState<'all' | 'healthy' | 'degraded'>('all');

  if (!dashboard) return null;

  // Primary: new k8s-resources collector data; fallback: old workloadHealth
  const deployments = dashboard.k8sDeployments?.length
    ? dashboard.k8sDeployments
    : (dashboard.workloadHealth?.deployments || []);
  const statefulSets = dashboard.k8sStatefulSets?.length
    ? dashboard.k8sStatefulSets
    : (dashboard.workloadHealth?.statefulsets || []);
  const daemonSets = dashboard.k8sDaemonSets?.length
    ? dashboard.k8sDaemonSets
    : (dashboard.workloadHealth?.daemonsets || []);

  // Combine and filter data
  const filteredWorkloads = useMemo(() => {
    let workloads = [];
    switch (selectedWorkloadType) {
      case 'statefulsets': workloads = statefulSets; break;
      case 'daemonsets': workloads = daemonSets; break;
      default: workloads = deployments;
    }

    return workloads.filter((w: any) => {
      const matchesSearch = w.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
        w.namespace.toLowerCase().includes(searchTerm.toLowerCase());

      const ready = w.readyReplicas || w.ready || w.numberReady || 0;
      const desired = w.replicas || w.desired || w.currentNumberScheduled || 0;
      const isHealthy = desired > 0 ? ready === desired : true;

      const matchesStatus = statusFilter === 'all' ||
        (statusFilter === 'healthy' && isHealthy) ||
        (statusFilter === 'degraded' && !isHealthy);

      return matchesSearch && matchesStatus;
    });
  }, [selectedWorkloadType, deployments, statefulSets, daemonSets, searchTerm, statusFilter]);

  const paginatedWorkloads = filteredWorkloads.slice(
    currentPage * ITEMS_PER_PAGE,
    (currentPage + 1) * ITEMS_PER_PAGE
  );

  const totalPages = Math.ceil(filteredWorkloads.length / ITEMS_PER_PAGE);

  const handleTypeChange = (type: 'deployments' | 'statefulsets' | 'daemonsets') => {
    setSelectedWorkloadType(type);
    setCurrentPage(0);
  };

  return (
    <div className="space-y-6">
      {/* Overview Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div className="bg-white p-5 rounded-xl border border-gray-200 shadow-sm">
          <div className="flex items-center gap-3 mb-2">
            <div className="w-10 h-10 bg-blue-50 text-blue-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-layer-group"></i>
            </div>
            <span className="text-sm font-medium text-gray-500 uppercase tracking-wider">Deployments</span>
          </div>
          <div className="text-2xl font-bold text-gray-900">{deployments.length}</div>
        </div>

        <div className="bg-white p-5 rounded-xl border border-gray-200 shadow-sm">
          <div className="flex items-center gap-3 mb-2">
            <div className="w-10 h-10 bg-indigo-50 text-indigo-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-database"></i>
            </div>
            <span className="text-sm font-medium text-gray-500 uppercase tracking-wider">StatefulSets</span>
          </div>
          <div className="text-2xl font-bold text-gray-900">{statefulSets.length}</div>
        </div>

        <div className="bg-white p-5 rounded-xl border border-gray-200 shadow-sm">
          <div className="flex items-center gap-3 mb-2">
            <div className="w-10 h-10 bg-teal-50 text-teal-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-network-wired"></i>
            </div>
            <span className="text-sm font-medium text-gray-500 uppercase tracking-wider">DaemonSets</span>
          </div>
          <div className="text-2xl font-bold text-gray-900">{daemonSets.length}</div>
        </div>

        <div className="bg-white p-5 rounded-xl border border-gray-200 shadow-sm">
          <div className="flex items-center gap-3 mb-2">
            <div className="w-10 h-10 bg-purple-50 text-purple-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-heartbeat"></i>
            </div>
            <span className="text-sm font-medium text-gray-500 uppercase tracking-wider">Health Map</span>
          </div>
          <div className="text-2xl font-bold text-gray-900">
            {Math.round(dashboard.workloadHealth?.clusterHealthScore || 0)}%
          </div>
        </div>
      </div>

      {/* Filters & Actions */}
      <div className="bg-white p-4 rounded-xl border border-gray-200 shadow-sm flex flex-col md:flex-row gap-4 items-center justify-between">
        <div className="flex items-center gap-2 bg-gray-100 p-1 rounded-lg w-full md:w-auto">
          <button
            onClick={() => handleTypeChange('deployments')}
            className={`px-4 py-2 rounded-md text-sm font-medium transition-all ${selectedWorkloadType === 'deployments' ? 'bg-white text-blue-600 shadow-sm' : 'text-gray-600 hover:text-gray-900'
              }`}
          >
            Deployments
          </button>
          <button
            onClick={() => handleTypeChange('statefulsets')}
            className={`px-4 py-2 rounded-md text-sm font-medium transition-all ${selectedWorkloadType === 'statefulsets' ? 'bg-white text-indigo-600 shadow-sm' : 'text-gray-600 hover:text-gray-900'
              }`}
          >
            StatefulSets
          </button>
          <button
            onClick={() => handleTypeChange('daemonsets')}
            className={`px-4 py-2 rounded-md text-sm font-medium transition-all ${selectedWorkloadType === 'daemonsets' ? 'bg-white text-teal-600 shadow-sm' : 'text-gray-600 hover:text-gray-900'
              }`}
          >
            DaemonSets
          </button>
        </div>

        <div className="flex items-center gap-3 w-full md:w-auto">
          <div className="relative flex-1 md:w-64">
            <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400"></i>
            <input
              type="text"
              placeholder="Search workloads..."
              value={searchTerm}
              onChange={(e) => { setSearchTerm(e.target.value); setCurrentPage(0); }}
              className="w-full pl-10 pr-4 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all"
            />
          </div>

          <select
            value={statusFilter}
            onChange={(e: any) => { setStatusFilter(e.target.value); setCurrentPage(0); }}
            className="px-4 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 outline-none"
          >
            <option value="all">All States</option>
            <option value="healthy">Healthy</option>
            <option value="degraded">Degraded</option>
          </select>
        </div>
      </div>

      {/* Main Content Table */}
      <div className="bg-white rounded-xl border border-gray-200 shadow-sm overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="bg-gray-50 border-bottom border-gray-200">
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Workload Name</th>
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Namespace</th>
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider text-center">Replicas</th>
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {paginatedWorkloads.map((workload: any, index: number) => {
                const ready = workload.readyReplicas || workload.ready || workload.numberReady || 0;
                const desired = workload.replicas || workload.desired || workload.currentNumberScheduled || 0;
                const isHealthy = desired > 0 ? ready === desired : true;

                return (
                  <tr key={index} className="hover:bg-gray-50 transition-colors group">
                    <td className="px-6 py-4">
                      <div className="flex items-center gap-3">
                        <div className={`w-8 h-8 rounded shadow-sm flex items-center justify-center flex-shrink-0 ${isHealthy ? 'bg-green-50 text-green-600' : 'bg-red-50 text-red-600'
                          }`}>
                          <i className={`fas ${selectedWorkloadType === 'deployments' ? 'fa-layer-group' :
                            selectedWorkloadType === 'statefulsets' ? 'fa-database' :
                              'fa-network-wired'
                            } text-sm`}></i>
                        </div>
                        <span className="font-bold text-gray-900 group-hover:text-blue-600 transition-colors">
                          {workload.name}
                        </span>
                      </div>
                    </td>
                    <td className="px-6 py-4">
                      <span className="text-sm text-gray-600 bg-gray-100 px-2 py-1 rounded">
                        {workload.namespace}
                      </span>
                    </td>
                    <td className="px-6 py-4">
                      <div className="flex flex-col items-center">
                        <span className="text-sm font-bold text-gray-900">{ready} / {desired}</span>
                        <div className="w-16 h-1.5 bg-gray-100 rounded-full mt-1.5 overflow-hidden">
                          <div
                            className={`h-full rounded-full ${isHealthy ? 'bg-green-500' : 'bg-red-500'}`}
                            style={{ width: `${desired > 0 ? (ready / desired) * 100 : 0}%` }}
                          ></div>
                        </div>
                      </div>
                    </td>
                    <td className="px-6 py-4">
                      <span className={`inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-bold ${isHealthy ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
                        }`}>
                        <div className={`w-2 h-2 rounded-full ${isHealthy ? 'bg-green-500' : 'bg-red-500'}`}></div>
                        {isHealthy ? 'Healthy' : 'Degraded'}
                      </span>
                    </td>
                  </tr>
                );
              })}

              {paginatedWorkloads.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-6 py-12 text-center text-gray-500">
                    <div className="flex flex-col items-center">
                      <i className="fas fa-search text-3xl mb-3 text-gray-300"></i>
                      <p>No {selectedWorkloadType} matching your filters</p>
                    </div>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        {/* Pagination Footer */}
        {totalPages > 1 && (
          <div className="px-6 py-4 bg-gray-50 border-t border-gray-200 flex items-center justify-between">
            <div className="text-sm text-gray-500">
              Showing <span className="font-medium text-gray-900">{(currentPage * ITEMS_PER_PAGE) + 1}</span> to <span className="font-medium text-gray-900">{Math.min((currentPage + 1) * ITEMS_PER_PAGE, filteredWorkloads.length)}</span> of <span className="font-medium text-gray-900">{filteredWorkloads.length}</span> results
            </div>
            <div className="flex gap-2">
              <button
                disabled={currentPage === 0}
                onClick={() => setCurrentPage(prev => prev - 1)}
                className="px-4 py-2 bg-white border border-gray-300 rounded-lg text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50 transition-colors"
              >
                Previous
              </button>
              <button
                disabled={currentPage === totalPages - 1}
                onClick={() => setCurrentPage(prev => prev + 1)}
                className="px-4 py-2 bg-white border border-gray-300 rounded-lg text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50 transition-colors"
              >
                Next
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default EksWorkloadsTab;
