/**
 * CloudK8s Cluster Table Component
 *
 * Displays EKS clusters in a table with actions for monitoring and details
 */

import React from 'react';
import { EKSCluster } from '../../pages/CloudK8sPage';
import Skeleton from '../Shared/Skeleton';

interface CloudK8sClusterTableProps {
  clusters: EKSCluster[];
  isLoading: boolean;
  onEnableMonitoring: (cluster: EKSCluster) => void;
  onViewDetails: (cluster: EKSCluster) => void;
}

const CloudK8sClusterTable: React.FC<CloudK8sClusterTableProps> = ({
  clusters,
  isLoading,
  onEnableMonitoring,
  onViewDetails
}) => {
  return (
    <div className="cloudk8s-card glass-card rounded-2xl p-6 md:p-8 fade-in">
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-xl font-bold text-gray-800 flex items-center">
          <i className="fab fa-aws text-orange-500 mr-3"></i>
          EKS Clusters
        </h2>
        <span className="text-sm text-gray-500">
          {clusters.length} cluster{clusters.length !== 1 ? 's' : ''} found
        </span>
      </div>

      {/* Loading State */}
      {isLoading && (
        <div className="space-y-3">
          {[1, 2, 3].map(i => (
            <Skeleton key={i} variant="rounded" height={60} className="w-full" />
          ))}
        </div>
      )}

      {/* Empty State */}
      {!isLoading && clusters.length === 0 && (
        <div className="text-center py-16">
          <div className="w-20 h-20 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-4">
            <i className="fas fa-dharmachakra text-gray-400 text-3xl"></i>
          </div>
          <p className="text-gray-600 font-medium">No EKS clusters found</p>
          <p className="text-gray-400 text-sm mt-1">Connect an AWS account to view your clusters</p>
        </div>
      )}

      {/* Clusters Table */}
      {!isLoading && clusters.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm table-enhanced rounded-lg overflow-hidden">
            <thead className="text-xs text-gray-700 uppercase bg-gradient-to-r from-gray-50 to-gray-100">
              <tr>
                <th className="px-6 py-4 text-left font-bold">Cluster Name</th>
                <th className="px-6 py-4 text-left font-bold hidden md:table-cell">Region</th>
                <th className="px-6 py-4 text-left font-bold hidden md:table-cell">Version</th>
                <th className="px-6 py-4 text-left font-bold">Connection</th>
                <th className="px-6 py-4 text-center font-bold">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {clusters.map(cluster => (
                <tr
                  key={`${cluster.name}-${cluster.region}`}
                  className="hover:bg-blue-50/50 transition-colors"
                >
                  <td className="px-6 py-4">
                    <div className="flex items-center">
                      <div className="w-8 h-8 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-lg flex items-center justify-center mr-3">
                        <i className="fas fa-dharmachakra text-white text-xs"></i>
                      </div>
                      <span className="font-semibold text-gray-800">{cluster.name}</span>
                    </div>
                  </td>
                  <td className="px-6 py-4 hidden md:table-cell">
                    <span className="text-gray-600">{cluster.region}</span>
                  </td>
                  <td className="px-6 py-4 hidden md:table-cell">
                    <span className="px-2 py-1 bg-gray-100 text-gray-700 rounded text-xs font-mono">
                      v{cluster.version}
                    </span>
                  </td>
                  <td className="px-6 py-4">
                    {cluster.connected ? (
                      <span className="status-badge bg-green-100 text-green-800 border border-green-200">
                        <i className="fas fa-check-circle mr-1.5"></i>
                        Connected
                      </span>
                    ) : (
                      <button
                        onClick={() => onEnableMonitoring(cluster)}
                        disabled={cluster.isEnabling}
                        className="px-3 py-1.5 text-xs font-semibold rounded-lg bg-gradient-to-r from-yellow-500 to-orange-500 text-white hover:from-yellow-600 hover:to-orange-600 disabled:opacity-50 disabled:cursor-not-allowed transition-all shadow-sm hover:shadow"
                      >
                        {cluster.isEnabling ? (
                          <>
                            <i className="fas fa-spinner fa-spin mr-1.5"></i>
                            Enabling...
                          </>
                        ) : (
                          <>
                            <i className="fas fa-magic mr-1.5"></i>
                            Enable Monitoring
                          </>
                        )}
                      </button>
                    )}
                  </td>
                  <td className="px-6 py-4 text-center">
                    <button
                      onClick={() => onViewDetails(cluster)}
                      className="inline-flex items-center px-4 py-2 text-xs font-semibold rounded-lg bg-gradient-to-r from-blue-500 to-indigo-600 text-white hover:from-blue-600 hover:to-indigo-700 transition-all shadow-sm hover:shadow"
                    >
                      <i className="fas fa-external-link-alt mr-1.5"></i>
                      Details
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

export default CloudK8sClusterTable;
