/**
 * GKE Node Pools Tab
 *
 * Displays all node pools for the cluster with autoscaling config,
 * machine type, disk, and status details. Data comes from GCP API
 * and is always available (even for unconnected clusters).
 */

import React from 'react';
import { GkeNodePoolInfo } from '../../../types/gkeTypes';

const GkeNodePoolsTab: React.FC<{ gkeData: any }> = ({ gkeData }) => {
  const { dashboard } = gkeData;
  const nodePools: GkeNodePoolInfo[] = dashboard?.nodePools ?? [];

  const getStatusChip = (status: string) => {
    const s = status?.toUpperCase();
    if (s === 'RUNNING') return 'bg-green-100 text-green-700';
    if (s === 'PROVISIONING') return 'bg-blue-100 text-blue-700';
    if (s === 'RECONCILING') return 'bg-amber-100 text-amber-700';
    if (s === 'STOPPING') return 'bg-red-100 text-red-700';
    return 'bg-gray-100 text-gray-600';
  };

  if (nodePools.length === 0) {
    return (
      <div className="bg-white rounded-xl border border-gray-200 p-12 flex flex-col items-center text-center">
        <i className="fas fa-server text-gray-200 text-5xl mb-4"></i>
        <h3 className="text-lg font-bold text-gray-700 mb-2">No Node Pools Found</h3>
        <p className="text-gray-400 text-sm">Node pool data could not be loaded for this cluster.</p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Summary cards */}
      <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
        <div className="bg-white p-5 rounded-xl border border-gray-200 shadow-sm">
          <div className="flex items-center gap-3 mb-2">
            <div className="w-10 h-10 bg-blue-50 text-blue-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-server"></i>
            </div>
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Node Pools</span>
          </div>
          <div className="text-2xl font-bold text-gray-900">{nodePools.length}</div>
        </div>

        <div className="bg-white p-5 rounded-xl border border-gray-200 shadow-sm">
          <div className="flex items-center gap-3 mb-2">
            <div className="w-10 h-10 bg-green-50 text-green-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-check-circle"></i>
            </div>
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Total Nodes</span>
          </div>
          <div className="text-2xl font-bold text-gray-900">
            {nodePools.reduce((sum, np) => sum + (np.nodeCount || 0), 0)}
          </div>
        </div>

        <div className="bg-white p-5 rounded-xl border border-gray-200 shadow-sm">
          <div className="flex items-center gap-3 mb-2">
            <div className="w-10 h-10 bg-purple-50 text-purple-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-expand-arrows-alt"></i>
            </div>
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Autoscaling</span>
          </div>
          <div className="text-2xl font-bold text-gray-900">
            {nodePools.filter(np => np.autoscalingEnabled).length}
          </div>
        </div>
      </div>

      {/* Node pools table */}
      <div className="bg-white rounded-xl border border-gray-200 shadow-sm overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="bg-gray-50">
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Pool Name</th>
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Machine Type</th>
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider text-center">Nodes</th>
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Autoscaling</th>
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">K8s Version</th>
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Disk</th>
                <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {nodePools.map((pool, idx) => (
                <tr key={idx} className="hover:bg-gray-50 transition-colors group">
                  <td className="px-6 py-4">
                    <div className="flex items-center gap-3">
                      <div className="w-8 h-8 rounded bg-blue-50 flex items-center justify-center flex-shrink-0">
                        <i className="fas fa-server text-blue-500 text-sm"></i>
                      </div>
                      <span className="font-bold text-gray-900">{pool.name}</span>
                    </div>
                  </td>
                  <td className="px-6 py-4">
                    <span className="text-sm text-gray-700 font-mono bg-gray-100 px-2 py-1 rounded">
                      {pool.machineType || '—'}
                    </span>
                  </td>
                  <td className="px-6 py-4 text-center">
                    <span className="text-lg font-bold text-gray-900">{pool.nodeCount}</span>
                  </td>
                  <td className="px-6 py-4">
                    {pool.autoscalingEnabled ? (
                      <div>
                        <span className="inline-flex items-center gap-1 px-2 py-1 bg-purple-100 text-purple-700 text-xs font-bold rounded-full">
                          <i className="fas fa-expand-arrows-alt text-xs"></i> Enabled
                        </span>
                        <p className="text-xs text-gray-500 mt-1">
                          {pool.minNodes} – {pool.maxNodes} nodes
                        </p>
                      </div>
                    ) : (
                      <span className="text-xs text-gray-400">Disabled</span>
                    )}
                  </td>
                  <td className="px-6 py-4">
                    <span className="text-sm text-gray-600">{pool.version || '—'}</span>
                  </td>
                  <td className="px-6 py-4">
                    <span className="text-sm text-gray-600">{pool.diskSizeGb ? `${pool.diskSizeGb} GB` : '—'}</span>
                  </td>
                  <td className="px-6 py-4">
                    <span className={`inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-bold ${getStatusChip(pool.status)}`}>
                      <div className="w-2 h-2 rounded-full bg-current"></div>
                      {pool.status || '—'}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};

export default GkeNodePoolsTab;
