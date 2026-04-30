import React from 'react';
import { AKSCluster } from '../../types/aksTypes';

interface AzureCloudK8sClusterTableProps {
  clusters: AKSCluster[];
  isLoading: boolean;
  onViewDetails: (cluster: AKSCluster) => void;
}

const AzureCloudK8sClusterTable: React.FC<AzureCloudK8sClusterTableProps> = ({
  clusters,
  isLoading,
  onViewDetails,
}) => {
  if (isLoading) {
    return (
      <div className="glass-card rounded-2xl p-6 md:p-8 fade-in">
        <h2 className="text-xl font-bold text-gray-800 mb-6">AKS Clusters</h2>
        <div className="text-center py-12">
          <div className="loading-spinner mx-auto"></div>
        </div>
      </div>
    );
  }

  if (clusters.length === 0) {
    return (
      <div className="glass-card rounded-2xl p-6 md:p-8 fade-in">
        <h2 className="text-xl font-bold text-gray-800 mb-6">AKS Clusters</h2>
        <div className="text-center py-12">
          <div className="text-gray-400 mb-4">
            <i className="fab fa-microsoft text-6xl"></i>
          </div>
          <p className="text-gray-600 text-lg font-medium mb-2">No AKS clusters found</p>
          <p className="text-gray-500 text-sm">
            Create an AKS cluster in your Azure subscription to get started
          </p>
        </div>
      </div>
    );
  }

  const getStatusBadge = (state?: string) => {
    if (state === 'Succeeded') {
      return 'bg-green-100 text-green-800';
    }
    if (state === 'Failed') {
      return 'bg-red-100 text-red-800';
    }
    return 'bg-yellow-100 text-yellow-800';
  };

  return (
    <div className="glass-card rounded-2xl p-6 md:p-8 fade-in">
      <h2 className="text-xl font-bold text-gray-800 mb-6">AKS Clusters</h2>
      <div className="overflow-x-auto">
        <table className="w-full text-sm table-enhanced rounded-lg overflow-hidden">
          <thead className="text-xs text-gray-700 uppercase">
            <tr>
              <th className="px-6 py-4 text-left font-bold">Name</th>
              <th className="px-6 py-4 text-left font-bold hidden md:table-cell">Resource Group</th>
              <th className="px-6 py-4 text-left font-bold hidden md:table-cell">Location</th>
              <th className="px-6 py-4 text-left font-bold hidden md:table-cell">Version</th>
              <th className="px-6 py-4 text-left font-bold hidden lg:table-cell">Status</th>
              <th className="px-6 py-4 text-left font-bold">Connection</th>
              <th className="px-6 py-4 text-center font-bold">Action</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200">
            {clusters.map((cluster) => (
              <tr
                key={`${cluster.name}-${cluster.resourceGroup}`}
                className="hover:bg-blue-50 transition-colors"
              >
                <td className="px-6 py-4 font-semibold text-gray-800">{cluster.name}</td>
                <td className="px-6 py-4 hidden md:table-cell text-gray-600">
                  <div className="flex items-center">
                    <i className="fas fa-layer-group text-gray-400 mr-2 text-xs"></i>
                    {cluster.resourceGroup}
                  </div>
                </td>
                <td className="px-6 py-4 hidden md:table-cell text-gray-600">
                  <div className="flex items-center">
                    <i className="fas fa-map-marker-alt text-gray-400 mr-2 text-xs"></i>
                    {cluster.location}
                  </div>
                </td>
                <td className="px-6 py-4 hidden md:table-cell text-gray-600">
                  {cluster.kubernetesVersion && (
                    <span className="px-2 py-1 bg-blue-100 text-blue-800 rounded text-xs font-medium">
                      {cluster.kubernetesVersion}
                    </span>
                  )}
                </td>
                <td className="px-6 py-4 hidden lg:table-cell">
                  <span className={`status-badge ${getStatusBadge(cluster.provisioningState)}`}>
                    {cluster.provisioningState || 'Unknown'}
                  </span>
                </td>
                <td className="px-6 py-4">
                  {cluster.connected ? (
                    <span className="status-badge bg-green-100 text-green-800">
                      <i className="fas fa-check-circle mr-1"></i>
                      Connected
                    </span>
                  ) : (
                    <span className="inline-flex items-center gap-1.5 px-3 py-1 text-xs font-semibold rounded-lg bg-amber-100 text-amber-700 border border-amber-200">
                      <i className="fas fa-robot"></i>
                      Deploy Agent
                    </span>
                  )}
                </td>
                <td className="px-6 py-4 text-center">
                  <button
                    onClick={() => onViewDetails(cluster)}
                    className="inline-block px-4 py-2 text-xs font-semibold rounded-lg bg-gradient-to-r from-blue-600 to-cyan-600 text-white hover:from-blue-700 hover:to-cyan-700 transition-all shadow-sm hover:shadow-md"
                  >
                    <i className="fas fa-chart-line mr-1"></i>
                    Details
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="mt-6 pt-4 border-t border-gray-200">
        <div className="flex items-center space-x-4 text-sm">
          <span className="text-gray-600">
            <i className="fas fa-server text-blue-500 mr-2"></i>
            Total Clusters: <span className="font-bold text-gray-800">{clusters.length}</span>
          </span>
          <span className="text-gray-600">
            <i className="fas fa-check-circle text-green-500 mr-2"></i>
            Connected: <span className="font-bold text-gray-800">{clusters.filter(c => c.connected).length}</span>
          </span>
          <span className="text-gray-600">
            <i className="fas fa-robot text-amber-500 mr-2"></i>
            Awaiting Agent: <span className="font-bold text-gray-800">{clusters.filter(c => !c.connected).length}</span>
          </span>
        </div>
      </div>
    </div>
  );
};

export default AzureCloudK8sClusterTable;
