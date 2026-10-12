import React from 'react';
import { Icon } from '@iconify/react';
import { EKSCluster } from '../../pages/CloudK8sPage';

interface CloudK8sClusterTableProps {
  clusters: EKSCluster[];
  isLoading: boolean;
  onViewDetails: (cluster: EKSCluster) => void;
}

const HEAD = 'px-5 py-3 font-semibold text-[11px] uppercase tracking-[0.07em] text-[rgba(0,0,0,0.45)]';
const COL  = 'px-5 py-4 text-[13px]';

/**
 * The column shows what the backend checks: whether the cluster's Container Insights performance
 * log group exists. It used to read "Connection: Connected / Not Connected", which no longer
 * matched once the check became an exact log-group match (demo-cut review S3). FEAT-006 rebuilds
 * the fleet with the agent's own connection state.
 */
export const CONTAINER_INSIGHTS_TOOLTIP =
  'Whether the Container Insights performance log group exists for the cluster. It says nothing about the XamOps agent.';

/** CONFIGURED, NOT_CONFIGURED or UNKNOWN; a list cached before the detail existed is UNKNOWN. */
export function containerInsightsState(cluster: EKSCluster): 'CONFIGURED' | 'NOT_CONFIGURED' | 'UNKNOWN' {
  const status = cluster.containerInsights?.status;
  return status === 'CONFIGURED' || status === 'NOT_CONFIGURED' ? status : 'UNKNOWN';
}

function unknownInsightsTitle(cluster: EKSCluster): string {
  const detail = cluster.containerInsights;
  if (detail?.reason === 'MISSING_AWS_PERMISSION') {
    return `XamOps couldn't check: the role is denied ${detail.action ?? 'logs:DescribeLogGroups'}.`;
  }
  return detail ? "XamOps couldn't check Container Insights for this cluster." : 'Not checked yet. Refresh the cluster list.';
}

const CloudK8sClusterTable: React.FC<CloudK8sClusterTableProps> = ({ clusters, isLoading, onViewDetails }) => {
  return (
    <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl shadow-sm overflow-hidden">
      <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between bg-[rgba(0,0,0,0.01)]">
        <h2 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)] flex items-center gap-2">
          <Icon icon="mdi:aws" width={16} color="#FF9900" />
          EKS Clusters
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
          <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.55)]">No EKS clusters found</p>
          <p className="text-[12px] text-[rgba(0,0,0,0.35)] mt-1">Connect an AWS account to view your clusters</p>
        </div>
      )}

      {!isLoading && clusters.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-left">
            <thead>
              <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
                <th className={HEAD}>Cluster Name</th>
                <th className={`${HEAD} hidden md:table-cell`}>Region</th>
                <th className={`${HEAD} hidden md:table-cell`}>Version</th>
                <th className={HEAD} title={CONTAINER_INSIGHTS_TOOLTIP}>Container Insights</th>
                <th className={`${HEAD} text-center`}>Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
              {clusters.map(cluster => (
                <tr key={`${cluster.name}-${cluster.region}`}
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
                    <span className="flex items-center gap-1.5 text-[12px] font-semibold text-[rgba(0,0,0,0.65)] bg-blue-50 border border-blue-100 px-2.5 py-1 rounded-full w-fit">
                      <Icon icon="mdi:map-marker-outline" width={11} color="#3B82F6" />
                      {cluster.region}
                    </span>
                  </td>
                  <td className={`${COL} hidden md:table-cell`}>
                    <span className="px-2 py-1 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.06)] text-[rgba(0,0,0,0.65)] rounded text-[11px] font-mono">
                      v{cluster.version}
                    </span>
                  </td>
                  <td className={COL} data-container-insights={containerInsightsState(cluster)}>
                    {containerInsightsState(cluster) === 'CONFIGURED' ? (
                      <span className="inline-flex items-center gap-1.5 px-2.5 py-1 bg-emerald-50 text-emerald-700 border border-emerald-100 rounded-full text-[11px] font-semibold">
                        <Icon icon="mdi:check-circle-outline" width={12} />
                        Configured
                      </span>
                    ) : containerInsightsState(cluster) === 'NOT_CONFIGURED' ? (
                      <span className="inline-flex items-center gap-1.5 px-2.5 py-1 bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)] border border-[rgba(0,0,0,0.08)] rounded-full text-[11px] font-semibold">
                        <Icon icon="mdi:close-circle-outline" width={12} />
                        Not configured
                      </span>
                    ) : (
                      <span
                        className="inline-flex items-center gap-1.5 px-2.5 py-1 bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)] border border-[rgba(0,0,0,0.08)] rounded-full text-[11px] font-semibold"
                        title={unknownInsightsTitle(cluster)}
                      >
                        <Icon icon="mdi:help-circle-outline" width={12} />
                        Unknown
                      </span>
                    )}
                  </td>
                  <td className={`${COL} text-center`}>
                    <button
                      onClick={() => onViewDetails(cluster)}
                      className="inline-flex items-center gap-1.5 px-3.5 py-2 text-[12px] font-semibold rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white transition-colors"
                    >
                      <Icon icon="mdi:open-in-new" width={12} />
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
