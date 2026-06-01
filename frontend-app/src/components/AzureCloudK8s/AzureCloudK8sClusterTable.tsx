import React from 'react';
import { Icon } from '@iconify/react';
import { AKSCluster } from '../../types/aksTypes';

interface AzureCloudK8sClusterTableProps {
  clusters: AKSCluster[];
  isLoading: boolean;
  onViewDetails: (cluster: AKSCluster) => void;
}

const HEAD = 'px-5 py-3 font-semibold text-[11px] uppercase tracking-[0.07em] text-[rgba(0,0,0,0.45)]';
const COL  = 'px-5 py-4 text-[13px]';

const statusStyle = (state?: string) => {
  if (state === 'Succeeded') return 'bg-emerald-50 text-emerald-700 border-emerald-100';
  if (state === 'Failed')    return 'bg-red-50 text-red-700 border-red-100';
  return 'bg-amber-50 text-amber-700 border-amber-100';
};

const AzureCloudK8sClusterTable: React.FC<AzureCloudK8sClusterTableProps> = ({ clusters, isLoading, onViewDetails }) => {
  return (
    <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl shadow-sm overflow-hidden">
      <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between bg-[rgba(0,0,0,0.01)]">
        <h2 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)] flex items-center gap-2">
          <Icon icon="mdi:microsoft-azure" width={16} color="#0078D4" />
          AKS Clusters
        </h2>
        <span className="text-[12px] text-[rgba(0,0,0,0.4)] font-semibold">
          {clusters.length} cluster{clusters.length !== 1 ? 's' : ''} found
        </span>
      </div>

      {isLoading && (
        <div className="p-5 space-y-3">
          {[1, 2, 3].map(i => (
            <div key={i} className="h-14 rounded-lg bg-[rgba(0,0,0,0.04)] animate-pulse" />
          ))}
        </div>
      )}

      {!isLoading && clusters.length === 0 && (
        <div className="py-16 text-center">
          <div className="w-14 h-14 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.06)] rounded-xl flex items-center justify-center mx-auto mb-4">
            <Icon icon="mdi:kubernetes" width={26} color="rgba(0,0,0,0.2)" />
          </div>
          <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.55)]">No AKS clusters found</p>
          <p className="text-[12px] text-[rgba(0,0,0,0.35)] mt-1">Create an AKS cluster in your Azure subscription to get started</p>
        </div>
      )}

      {!isLoading && clusters.length > 0 && (
        <>
          <div className="overflow-x-auto">
            <table className="w-full text-left">
              <thead>
                <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
                  <th className={HEAD}>Name</th>
                  <th className={`${HEAD} hidden md:table-cell`}>Resource Group</th>
                  <th className={`${HEAD} hidden md:table-cell`}>Location</th>
                  <th className={`${HEAD} hidden md:table-cell`}>Version</th>
                  <th className={`${HEAD} hidden lg:table-cell`}>Status</th>
                  <th className={HEAD}>Connection</th>
                  <th className={`${HEAD} text-center`}>Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
                {clusters.map(cluster => (
                  <tr key={`${cluster.name}-${cluster.resourceGroup}`}
                    className="hover:bg-[rgba(0,0,0,0.01)] transition-colors group">
                    <td className={COL}>
                      <div className="flex items-center gap-3">
                        <div className="w-8 h-8 bg-blue-50 border border-blue-100 rounded-lg flex items-center justify-center">
                          <Icon icon="mdi:kubernetes" width={15} color="#326CE5" />
                        </div>
                        <span className="font-semibold text-[rgba(0,0,0,0.88)] group-hover:text-indigo-600 transition-colors">
                          {cluster.name}
                        </span>
                      </div>
                    </td>
                    <td className={`${COL} hidden md:table-cell`}>
                      <span className="flex items-center gap-1.5 text-[12px] font-semibold text-[rgba(0,0,0,0.65)] bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.06)] px-2.5 py-1 rounded-full w-fit">
                        <Icon icon="mdi:layers-outline" width={11} color="rgba(0,0,0,0.35)" />
                        {cluster.resourceGroup}
                      </span>
                    </td>
                    <td className={`${COL} hidden md:table-cell`}>
                      <span className="flex items-center gap-1.5 text-[12px] font-semibold text-[rgba(0,0,0,0.65)] bg-blue-50 border border-blue-100 px-2.5 py-1 rounded-full w-fit">
                        <Icon icon="mdi:map-marker-outline" width={11} color="#3B82F6" />
                        {cluster.location}
                      </span>
                    </td>
                    <td className={`${COL} hidden md:table-cell`}>
                      {cluster.kubernetesVersion && (
                        <span className="px-2 py-1 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.06)] text-[rgba(0,0,0,0.65)] rounded text-[11px] font-mono">
                          {cluster.kubernetesVersion}
                        </span>
                      )}
                    </td>
                    <td className={`${COL} hidden lg:table-cell`}>
                      <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 border rounded-full text-[11px] font-semibold ${statusStyle(cluster.provisioningState)}`}>
                        {cluster.provisioningState || 'Unknown'}
                      </span>
                    </td>
                    <td className={COL}>
                      {cluster.connected ? (
                        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 bg-emerald-50 text-emerald-700 border border-emerald-100 rounded-full text-[11px] font-semibold">
                          <Icon icon="mdi:check-circle-outline" width={12} />
                          Connected
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 bg-amber-50 text-amber-700 border border-amber-100 rounded-full text-[11px] font-semibold">
                          <Icon icon="mdi:robot-outline" width={12} />
                          Deploy Agent
                        </span>
                      )}
                    </td>
                    <td className={`${COL} text-center`}>
                      <button onClick={() => onViewDetails(cluster)}
                        className="inline-flex items-center gap-1.5 px-3.5 py-2 text-[12px] font-semibold rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white transition-colors">
                        <Icon icon="mdi:chart-line" width={12} />
                        Details
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="px-5 py-3 bg-[rgba(0,0,0,0.01)] border-t border-[rgba(0,0,0,0.06)] flex items-center gap-5 text-[12px] text-[rgba(0,0,0,0.45)] font-semibold">
            <span>Total: <span className="text-[rgba(0,0,0,0.75)]">{clusters.length}</span></span>
            <span className="text-emerald-600">Connected: {clusters.filter(c => c.connected).length}</span>
            <span className="text-amber-600">Awaiting Agent: {clusters.filter(c => !c.connected).length}</span>
          </div>
        </>
      )}
    </div>
  );
};

export default AzureCloudK8sClusterTable;
