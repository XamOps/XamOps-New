/**
 * EKS Containers Tab - Enhanced Version
 *
 * Professional container monitoring with detailed metrics, filtering, and pagination
 */

import React, { useState, useMemo } from 'react';
import { ContainerWithMetrics } from '../../../types/eksTypes';

interface EksContainersTabProps {
  eksData: any;
}

const EksContainersTab: React.FC<EksContainersTabProps> = ({ eksData }) => {
  const {
    filteredContainers,
    containerSearchQuery,
    setContainerSearchQuery,
    containerNamespaceFilter,
    setContainerNamespaceFilter,
    uniqueNamespaces,
    getContainersCountByState,
    allContainersWithMetrics
  } = eksData;

  // Pagination state
  const [currentPage, setCurrentPage] = useState(1);
  const containersPerPage = 10;

  // State filter
  const [stateFilter, setStateFilter] = useState('');

  // Selected container for detailed view
  const [selectedContainer, setSelectedContainer] = useState<ContainerWithMetrics | null>(null);

  // Apply state filter on top of existing filters
  const stateFilteredContainers = useMemo(() => {
    let result = stateFilter
      ? filteredContainers.filter((c: ContainerWithMetrics) => c.state === stateFilter)
      : [...filteredContainers];

    // Sort by metrics presence first, then by CPU usage
    return result.sort((a: any, b: any) => {
      const aHasMetrics = (a.cpuUsage > 0 && a.memoryUsage > 0) ? 1 : 0;
      const bHasMetrics = (b.cpuUsage > 0 && b.memoryUsage > 0) ? 1 : 0;
      if (aHasMetrics !== bHasMetrics) return bHasMetrics - aHasMetrics;
      return (b.cpuUsage || 0) - (a.cpuUsage || 0);
    });
  }, [filteredContainers, stateFilter]);

  // Pagination calculations
  const totalPages = Math.ceil(stateFilteredContainers.length / containersPerPage);
  const paginatedContainers = useMemo(() => {
    const startIndex = (currentPage - 1) * containersPerPage;
    return stateFilteredContainers.slice(startIndex, startIndex + containersPerPage);
  }, [stateFilteredContainers, currentPage]);

  // Reset to page 1 when filters change
  React.useEffect(() => {
    setCurrentPage(1);
  }, [containerSearchQuery, containerNamespaceFilter, stateFilter]);

  // Format bytes to MB
  const formatMemory = (bytes: number) => {
    return ((bytes || 0) / (1024 * 1024)).toFixed(2);
  };

  // Format CPU (milliCPU to CPU cores)
  const formatCpu = (milliCpu: number) => {
    return ((milliCpu || 0) / 1000).toFixed(3);
  };

  return (
    <div className="space-y-8">
      {/* Status Summary with Modern Cards */}
      <section className="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
        <h3 className="text-xl font-bold text-gray-900 mb-6 flex items-center gap-3">
          <div className="w-10 h-10 bg-purple-50 rounded-lg flex items-center justify-center">
            <i className="fas fa-boxes text-purple-600 text-lg"></i>
          </div>
          Container Status Overview
        </h3>

        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          {/* Running */}
          <div className="bg-white rounded-xl shadow-sm p-5 border border-gray-200">
            <div className="flex items-center justify-between mb-3">
              <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Running</span>
              <div className="w-8 h-8 rounded-lg bg-green-100 flex items-center justify-center">
                <i className="fas fa-check-circle text-green-600 text-sm"></i>
              </div>
            </div>
            <div className="text-3xl font-bold text-gray-900 mb-1">
              {getContainersCountByState('running')}
            </div>
            <div className="text-xs text-gray-600">Active containers</div>
          </div>

          {/* Waiting */}
          <div className="bg-white rounded-xl shadow-sm p-5 border border-gray-200">
            <div className="flex items-center justify-between mb-3">
              <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Waiting</span>
              <div className="w-8 h-8 rounded-lg bg-amber-100 flex items-center justify-center">
                <i className="fas fa-clock text-amber-600 text-sm"></i>
              </div>
            </div>
            <div className="text-3xl font-bold text-gray-900 mb-1">
              {getContainersCountByState('waiting')}
            </div>
            <div className="text-xs text-gray-600">Starting up</div>
          </div>

          {/* Terminated */}
          <div className="bg-white rounded-xl shadow-sm p-5 border border-gray-200">
            <div className="flex items-center justify-between mb-3">
              <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Terminated</span>
              <div className="w-8 h-8 rounded-lg bg-red-100 flex items-center justify-center">
                <i className="fas fa-times-circle text-red-600 text-sm"></i>
              </div>
            </div>
            <div className="text-3xl font-bold text-gray-900 mb-1">
              {getContainersCountByState('terminated')}
            </div>
            <div className="text-xs text-gray-600">Stopped</div>
          </div>

          {/* Total */}
          <div className="bg-white rounded-xl shadow-sm p-5 border border-gray-200">
            <div className="flex items-center justify-between mb-3">
              <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Total</span>
              <div className="w-8 h-8 rounded-lg bg-purple-100 flex items-center justify-center">
                <i className="fas fa-cube text-purple-600 text-sm"></i>
              </div>
            </div>
            <div className="text-3xl font-bold text-gray-900 mb-1">
              {allContainersWithMetrics.length}
            </div>
            <div className="text-xs text-gray-600">All containers</div>
          </div>
        </div>
      </section>

      {/* Container List */}
      <section className="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
        <div className="flex flex-wrap items-center justify-between gap-4 mb-6">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-purple-50 rounded-lg flex items-center justify-center">
              <i className="fas fa-list text-purple-600 text-lg"></i>
            </div>
            <div>
              <h3 className="text-xl font-bold text-gray-900">Container Details</h3>
              <p className="text-sm text-gray-600">
                {stateFilteredContainers.length} container{stateFilteredContainers.length !== 1 ? 's' : ''}
              </p>
            </div>
          </div>

          {/* Filters */}
          <div className="flex gap-3 flex-wrap">
            <div className="relative">
              <i className="fas fa-search absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400"></i>
              <input
                type="text"
                placeholder="Search containers..."
                value={containerSearchQuery}
                onChange={(e) => setContainerSearchQuery(e.target.value)}
                className="pl-10 pr-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-purple-500 text-sm font-medium"
              />
            </div>
            <select
              value={containerNamespaceFilter}
              onChange={(e) => setContainerNamespaceFilter(e.target.value)}
              className="px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-purple-500 text-sm font-medium"
            >
              <option value="">All Namespaces</option>
              {uniqueNamespaces.map((ns: string) => (
                <option key={ns} value={ns}>
                  {ns}
                </option>
              ))}
            </select>
            <select
              value={stateFilter}
              onChange={(e) => setStateFilter(e.target.value)}
              className="px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-purple-500 text-sm font-medium"
            >
              <option value="">All States</option>
              <option value="running">Running</option>
              <option value="waiting">Waiting</option>
              <option value="terminated">Terminated</option>
            </select>
          </div>
        </div>

        {/* Container Table */}
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  Container
                </th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  Pod
                </th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  Namespace
                </th>
                <th className="text-center px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  State
                </th>
                <th className="text-center px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  Restarts
                </th>
                <th className="text-center px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  CPU
                </th>
                <th className="text-center px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  Memory
                </th>
                <th className="text-center px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  Details
                </th>
              </tr>
            </thead>
            <tbody>
              {paginatedContainers.map((container: ContainerWithMetrics, index: number) => (
                <tr
                  key={container.id || index}
                  className="border-t border-gray-100 hover:bg-purple-50 transition-colors"
                >
                  <td className="px-4 py-4 text-sm">
                    <div className="flex items-center gap-2">
                      <div
                        className={`w-2 h-2 rounded-full ${container.state === 'running'
                          ? 'bg-green-500 animate-pulse'
                          : container.state === 'waiting'
                            ? 'bg-amber-500'
                            : 'bg-red-500'
                          }`}
                      ></div>
                      <span className="font-bold text-gray-900">{container.name}</span>
                    </div>
                  </td>
                  <td className="px-4 py-4 text-sm text-gray-600 font-mono">{container.pod}</td>
                  <td className="px-4 py-4 text-sm">
                    <span className="inline-flex items-center gap-1 px-2 py-1 bg-blue-50 text-blue-700 rounded-lg text-xs font-semibold">
                      <i className="fas fa-cube text-xs"></i>
                      {container.namespace}
                    </span>
                  </td>
                  <td className="px-4 py-4 text-sm text-center">
                    <span
                      className={`px-2 py-1 rounded-full text-xs font-bold ${container.state === 'running'
                        ? 'bg-green-100 text-green-700'
                        : container.state === 'waiting'
                          ? 'bg-amber-100 text-amber-700'
                          : 'bg-red-100 text-red-700'
                        }`}
                    >
                      {container.state}
                    </span>
                  </td>
                  <td className="px-4 py-4 text-sm text-center">
                    <span
                      className={`inline-flex items-center justify-center px-2 py-1 rounded-lg font-bold text-xs ${container.restartCount > 0
                        ? 'bg-amber-100 text-amber-700'
                        : 'bg-green-100 text-green-700'
                        }`}
                    >
                      {container.restartCount}
                    </span>
                  </td>
                  <td className="px-4 py-4 text-sm text-center">
                    <div className="flex flex-col items-center">
                      <span className="text-xs text-gray-500 font-medium">CPU Usage</span>
                      <span className="font-bold text-gray-900">{formatCpu(container.cpuUsage)}</span>
                      <span className="text-xs text-gray-500">CPU cores</span>
                      <span className="text-xs font-mono text-gray-700">({container.cpuUsage.toFixed(0)}m)</span>
                    </div>
                  </td>
                  <td className="px-4 py-4 text-sm text-center">
                    <div className="flex flex-col items-center">
                      <span className="text-xs text-gray-500 font-medium">Memory Usage</span>
                      <span className="font-bold text-gray-900">{formatMemory(container.memoryUsage)}</span>
                      <span className="text-xs text-gray-500">MB</span>
                      <span className="text-xs font-mono text-gray-700">({container.memoryUsage.toFixed(0)} bytes)</span>
                    </div>
                  </td>
                  <td className="px-4 py-4 text-sm text-center">
                    <button
                      onClick={() => setSelectedContainer(container)}
                      className="w-8 h-8 bg-purple-100 hover:bg-purple-200 rounded-lg flex items-center justify-center transition-colors mx-auto"
                    >
                      <i className="fas fa-info-circle text-purple-600"></i>
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {stateFilteredContainers.length === 0 && (
            <div className="text-center py-12 bg-[#f6f8fa] rounded-lg border border-dashed border-[#d0d7de] mt-4">
              <i className="fas fa-filter text-[#8b949e] text-2xl mb-3"></i>
              <p className="text-[13px] text-[#57606a] font-medium">No containers match your filters</p>
              <button
                onClick={() => {
                  setContainerSearchQuery('');
                  setContainerNamespaceFilter('');
                  setStateFilter('');
                }}
                className="mt-3 px-4 py-2 bg-purple-100 text-purple-700 rounded-lg text-sm font-semibold hover:bg-purple-200 transition-colors"
              >
                Clear Filters
              </button>
            </div>
          )}
        </div>

        {/* Pagination Controls */}
        {totalPages > 1 && (
          <div className="mt-6 flex items-center justify-between pt-6 border-t border-gray-200">
            <div className="text-sm text-gray-600">
              Showing {((currentPage - 1) * containersPerPage) + 1} to{' '}
              {Math.min(currentPage * containersPerPage, stateFilteredContainers.length)} of{' '}
              {stateFilteredContainers.length} containers
            </div>
            <div className="flex items-center gap-2">
              <button
                onClick={() => setCurrentPage((p) => Math.max(1, p - 1))}
                disabled={currentPage === 1}
                className="px-3 py-1.5 border border-gray-300 rounded-lg text-sm font-semibold text-gray-700 disabled:opacity-40 disabled:cursor-not-allowed hover:bg-gray-100 transition-colors"
              >
                <i className="fas fa-chevron-left"></i>
              </button>
              <span className="text-sm font-semibold text-gray-700">
                Page {currentPage} of {totalPages}
              </span>
              <button
                onClick={() => setCurrentPage((p) => Math.min(totalPages, p + 1))}
                disabled={currentPage === totalPages}
                className="px-3 py-1.5 border border-gray-300 rounded-lg text-sm font-semibold text-gray-700 disabled:opacity-40 disabled:cursor-not-allowed hover:bg-gray-100 transition-colors"
              >
                <i className="fas fa-chevron-right"></i>
              </button>
            </div>
          </div>
        )}
      </section>

      {/* Detailed Container View Modal */}
      {selectedContainer && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-xl shadow-2xl max-w-4xl w-full max-h-[90vh] overflow-y-auto">
            {/* Modal Header */}
            <div className="sticky top-0 bg-white border-b border-gray-200 px-6 py-4 flex items-center justify-between">
              <div>
                <h3 className="text-xl font-bold text-gray-900 flex items-center gap-2">
                  <div
                    className={`w-3 h-3 rounded-full ${selectedContainer.state === 'running'
                      ? 'bg-green-500 animate-pulse'
                      : selectedContainer.state === 'waiting'
                        ? 'bg-amber-500'
                        : 'bg-red-500'
                      }`}
                  ></div>
                  {selectedContainer.name}
                </h3>
                <p className="text-sm text-gray-600 mt-1">Container Details</p>
              </div>
              <button
                onClick={() => setSelectedContainer(null)}
                className="w-10 h-10 bg-gray-100 hover:bg-gray-200 rounded-lg flex items-center justify-center transition-colors"
              >
                <i className="fas fa-times text-gray-600"></i>
              </button>
            </div>

            {/* Modal Content */}
            <div className="p-6 space-y-6">
              {/* Status Overview */}
              <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                <div className="bg-gray-50 rounded-xl p-4 border border-gray-200">
                  <div className="flex items-center gap-3">
                    <div
                      className={`w-10 h-10 rounded-lg flex items-center justify-center ${selectedContainer.state === 'running'
                        ? 'bg-green-100'
                        : selectedContainer.state === 'waiting'
                          ? 'bg-amber-100'
                          : 'bg-red-100'
                        }`}
                    >
                      <i
                        className={`fas ${selectedContainer.state === 'running'
                          ? 'fa-check-circle text-green-600'
                          : selectedContainer.state === 'waiting'
                            ? 'fa-clock text-amber-600'
                            : 'fa-times-circle text-red-600'
                          }`}
                      ></i>
                    </div>
                    <div>
                      <p className="text-xs text-gray-600 font-semibold uppercase tracking-wide">State</p>
                      <p className="text-lg font-bold text-gray-900 capitalize">{selectedContainer.state}</p>
                    </div>
                  </div>
                </div>

                <div className="bg-gray-50 rounded-xl p-4 border border-gray-200">
                  <div className="flex items-center gap-3">
                    <div
                      className={`w-10 h-10 rounded-lg flex items-center justify-center ${selectedContainer.ready ? 'bg-green-100' : 'bg-red-100'
                        }`}
                    >
                      <i
                        className={`fas fa-heartbeat ${selectedContainer.ready ? 'text-green-600' : 'text-red-600'
                          }`}
                      ></i>
                    </div>
                    <div>
                      <p className="text-xs text-gray-600 font-semibold uppercase tracking-wide">Ready</p>
                      <p className="text-lg font-bold text-gray-900">{selectedContainer.ready ? 'Yes' : 'No'}</p>
                    </div>
                  </div>
                </div>

                <div className="bg-gray-50 rounded-xl p-4 border border-gray-200">
                  <div className="flex items-center gap-3">
                    <div
                      className={`w-10 h-10 rounded-lg flex items-center justify-center ${selectedContainer.restartCount > 0 ? 'bg-amber-100' : 'bg-green-100'
                        }`}
                    >
                      <i
                        className={`fas fa-redo ${selectedContainer.restartCount > 0 ? 'text-amber-600' : 'text-green-600'
                          }`}
                      ></i>
                    </div>
                    <div>
                      <p className="text-xs text-gray-600 font-semibold uppercase tracking-wide">Restarts</p>
                      <p className="text-lg font-bold text-gray-900">{selectedContainer.restartCount}</p>
                    </div>
                  </div>
                </div>
              </div>

              {/* Container Information */}
              <div className="bg-white rounded-xl p-6 border border-gray-200">
                <h4 className="text-lg font-bold text-gray-900 mb-4 flex items-center gap-2">
                  <i className="fas fa-info-circle text-purple-600"></i>
                  Container Information
                </h4>
                <div className="space-y-3">
                  <div className="flex items-start">
                    <span className="text-sm font-semibold text-gray-600 w-40">Container Name:</span>
                    <span className="text-sm text-gray-900 font-mono flex-1">{selectedContainer.name}</span>
                  </div>
                  <div className="flex items-start">
                    <span className="text-sm font-semibold text-gray-600 w-40">Pod:</span>
                    <span className="text-sm text-gray-900 font-mono flex-1">{selectedContainer.pod}</span>
                  </div>
                  <div className="flex items-start">
                    <span className="text-sm font-semibold text-gray-600 w-40">Namespace:</span>
                    <span className="inline-flex items-center gap-1 px-2 py-1 bg-blue-50 text-blue-700 rounded-lg text-xs font-semibold">
                      <i className="fas fa-cube text-xs"></i>
                      {selectedContainer.namespace}
                    </span>
                  </div>
                  <div className="flex items-start">
                    <span className="text-sm font-semibold text-gray-600 w-40">Image:</span>
                    <span className="text-sm text-gray-900 font-mono flex-1 break-all">{selectedContainer.image}</span>
                  </div>
                  <div className="flex items-start">
                    <span className="text-sm font-semibold text-gray-600 w-40">Container ID:</span>
                    <span className="text-sm text-gray-900 font-mono flex-1 break-all">{selectedContainer.id}</span>
                  </div>
                </div>
              </div>

              {/* Resource Usage */}
              <div className="bg-white rounded-xl p-6 border border-gray-200">
                <h4 className="text-lg font-bold text-gray-900 mb-4 flex items-center gap-2">
                  <i className="fas fa-chart-line text-purple-600"></i>
                  Resource Usage
                </h4>
                <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                  {/* CPU Usage */}
                  <div className="bg-blue-50 rounded-xl p-4 border border-blue-200">
                    <div className="flex items-center justify-between mb-3">
                      <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">CPU Usage</span>
                      <div className="w-8 h-8 rounded-lg bg-blue-100 flex items-center justify-center">
                        <i className="fas fa-microchip text-blue-600 text-sm"></i>
                      </div>
                    </div>
                    <div className="text-2xl font-bold text-gray-900 mb-1">
                      {formatCpu(selectedContainer.cpuUsage)}
                    </div>
                    <div className="text-xs text-gray-600">CPU cores</div>
                    <div className="text-xs text-gray-500 mt-2">({selectedContainer.cpuUsage.toFixed(0)}m)</div>
                  </div>

                  {/* Memory Usage */}
                  <div className="bg-green-50 rounded-xl p-4 border border-green-200">
                    <div className="flex items-center justify-between mb-3">
                      <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Memory Usage</span>
                      <div className="w-8 h-8 rounded-lg bg-green-100 flex items-center justify-center">
                        <i className="fas fa-memory text-green-600 text-sm"></i>
                      </div>
                    </div>
                    <div className="text-2xl font-bold text-gray-900 mb-1">
                      {formatMemory(selectedContainer.memoryUsage)}
                    </div>
                    <div className="text-xs text-gray-600">MB</div>
                    <div className="text-xs text-gray-500 mt-2">({selectedContainer.memoryUsage.toFixed(0)} bytes)</div>
                  </div>

                  {/* Filesystem Usage */}
                  <div className="bg-purple-50 rounded-xl p-4 border border-purple-200">
                    <div className="flex items-center justify-between mb-3">
                      <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Filesystem</span>
                      <div className="w-8 h-8 rounded-lg bg-purple-100 flex items-center justify-center">
                        <i className="fas fa-hdd text-purple-600 text-sm"></i>
                      </div>
                    </div>
                    <div className="text-2xl font-bold text-gray-900 mb-1">
                      {formatMemory(selectedContainer.fsUsage)}
                    </div>
                    <div className="text-xs text-gray-600">MB</div>
                    <div className="text-xs text-gray-500 mt-2">({selectedContainer.fsUsage.toFixed(0)} bytes)</div>
                  </div>
                </div>
              </div>

              {/* Health & Status */}
              <div className="bg-white rounded-xl p-6 border border-gray-200">
                <h4 className="text-lg font-bold text-gray-900 mb-4 flex items-center gap-2">
                  <i className="fas fa-heart text-purple-600"></i>
                  Health & Status Indicators
                </h4>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
                    <div className="flex items-center justify-between mb-2">
                      <span className="text-sm font-semibold text-gray-700">Container State</span>
                      <span
                        className={`px-3 py-1 rounded-full text-xs font-bold ${selectedContainer.state === 'running'
                          ? 'bg-green-100 text-green-700'
                          : selectedContainer.state === 'waiting'
                            ? 'bg-amber-100 text-amber-700'
                            : 'bg-red-100 text-red-700'
                          }`}
                      >
                        {selectedContainer.state.toUpperCase()}
                      </span>
                    </div>
                    <p className="text-xs text-gray-600">
                      {selectedContainer.state === 'running'
                        ? 'Container is running and operational'
                        : selectedContainer.state === 'waiting'
                          ? 'Container is waiting to start'
                          : 'Container has terminated'}
                    </p>
                  </div>

                  <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
                    <div className="flex items-center justify-between mb-2">
                      <span className="text-sm font-semibold text-gray-700">Readiness</span>
                      <span
                        className={`px-3 py-1 rounded-full text-xs font-bold ${selectedContainer.ready ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
                          }`}
                      >
                        {selectedContainer.ready ? 'READY' : 'NOT READY'}
                      </span>
                    </div>
                    <p className="text-xs text-gray-600">
                      {selectedContainer.ready
                        ? 'Container is ready to accept traffic'
                        : 'Container is not ready to accept traffic'}
                    </p>
                  </div>

                  <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
                    <div className="flex items-center justify-between mb-2">
                      <span className="text-sm font-semibold text-gray-700">Restart Count</span>
                      <span
                        className={`px-3 py-1 rounded-full text-xs font-bold ${selectedContainer.restartCount === 0
                          ? 'bg-green-100 text-green-700'
                          : selectedContainer.restartCount < 5
                            ? 'bg-amber-100 text-amber-700'
                            : 'bg-red-100 text-red-700'
                          }`}
                      >
                        {selectedContainer.restartCount} RESTART{selectedContainer.restartCount !== 1 ? 'S' : ''}
                      </span>
                    </div>
                    <p className="text-xs text-gray-600">
                      {selectedContainer.restartCount === 0
                        ? 'Container has not restarted'
                        : selectedContainer.restartCount < 5
                          ? 'Container has restarted a few times'
                          : 'Container is experiencing frequent restarts - investigate logs'}
                    </p>
                  </div>

                  <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
                    <div className="flex items-center justify-between mb-2">
                      <span className="text-sm font-semibold text-gray-700">Resource Health</span>
                      <span
                        className={`px-3 py-1 rounded-full text-xs font-bold ${selectedContainer.cpuUsage < 500 && selectedContainer.memoryUsage < 500 * 1024 * 1024
                          ? 'bg-green-100 text-green-700'
                          : 'bg-amber-100 text-amber-700'
                          }`}
                      >
                        {selectedContainer.cpuUsage < 500 && selectedContainer.memoryUsage < 500 * 1024 * 1024
                          ? 'NORMAL'
                          : 'HIGH'}
                      </span>
                    </div>
                    <p className="text-xs text-gray-600">
                      {selectedContainer.cpuUsage < 500 && selectedContainer.memoryUsage < 500 * 1024 * 1024
                        ? 'Resource usage is within normal range'
                        : 'Resource usage is elevated - monitor closely'}
                    </p>
                  </div>
                </div>
              </div>

              {/* Info Footer */}
              <div className="bg-purple-50 rounded-lg p-4 border border-purple-200">
                <div className="flex items-start gap-3">
                  <div className="w-8 h-8 bg-purple-600 rounded-lg flex items-center justify-center flex-shrink-0">
                    <i className="fas fa-info-circle text-white text-sm"></i>
                  </div>
                  <div>
                    <h4 className="font-semibold text-gray-900 text-sm mb-1">Container Monitoring</h4>
                    <p className="text-xs text-gray-700 leading-relaxed">
                      This detailed view shows all available metrics for the container. Monitor resource usage patterns, restart counts, and health indicators to ensure optimal performance. Frequent restarts may indicate application issues or resource constraints that need investigation.
                    </p>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default EksContainersTab;
