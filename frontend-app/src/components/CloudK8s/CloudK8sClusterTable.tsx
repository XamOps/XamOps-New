import React, { useEffect, useState } from 'react';
import { Icon } from '@iconify/react';
import { EKSCluster } from '../../pages/CloudK8sPage';
import { StatusMark, type StatusState } from './shared/StatusMark';
import { BUTTON, RULE, INK, MUTED, FAINT } from '../EksDetails/sections/automation/automationUi';
import CloudK8sNoClusters from './CloudK8sNoClusters';

interface CloudK8sClusterTableProps {
  clusters: EKSCluster[];
  isLoading: boolean;
  onViewDetails: (cluster: EKSCluster) => void;
  /** The errorKind from useK8sClusters (unauthorized/notFound/server/network/html/invalid/incomplete); drives the error state. */
  error?: string | null;
  /** Reload the cluster list (the error state's Retry). Non-forced. */
  onRetry?: () => void;
  /** Force a fresh scan of the account's regions (the empty state's Re-scan). */
  onRescan?: () => void;
  /** A refresh over existing rows: keep the rows on screen, never fall back to the skeleton. */
  isRefreshing?: boolean;
}

/** The hairline that separates the header from the rows and the rows from each other (RULE token, no raw rgba border). */
const HAIRLINE = `border-b ${RULE}`;
const HEAD = `px-4 py-2.5 text-left text-[11px] font-semibold uppercase tracking-[0.07em] ${MUTED}`;
const COL = `px-4 py-3 align-middle text-[13px] ${INK}`;

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

/** The EKS lifecycle status, as a StatusMark dot+word (never a coloured pill). */
function clusterStatusMark(cluster: EKSCluster): { state: StatusState; label: string } {
  switch (cluster.status) {
    case 'ACTIVE':
      return { state: 'healthy', label: 'Active' };
    case 'CREATING':
      return { state: 'attention', label: 'creating' };
    case 'UPDATING':
      return { state: 'attention', label: 'updating' };
    case 'DELETING':
      return { state: 'attention', label: 'deleting' };
    case 'FAILED':
      return { state: 'error', label: 'Failed' };
    default:
      // Missing / unrecognised status: an em-dash, so we never invent a state we don't have.
      return { state: 'unknown', label: '—' };
  }
}

/** Honest, error-kind-specific copy for the error state (design §4). Every kind has a title and a line. */
function errorCopy(kind: string): { title: string; detail: string } {
  switch (kind) {
    case 'unauthorized':
      return {
        title: "You're not signed in",
        detail: 'Your session may have expired. Sign in again, then retry.',
      };
    case 'network':
      return {
        title: "Couldn't reach XamOps",
        detail: 'Check your connection, then try again.',
      };
    case 'incomplete':
      return {
        title: 'The scan finished with gaps',
        detail: "XamOps couldn't check every region, so the list may be incomplete. Retry to scan again.",
      };
    case 'invalid':
      return {
        title: "Couldn't read the cluster list",
        detail: "XamOps got an answer it didn't understand. Retry, and tell us if it keeps happening.",
      };
    case 'server':
    default:
      return {
        title: "Couldn't load your clusters",
        detail: 'Something went wrong on our side. Try again in a moment.',
      };
  }
}

/** The muted detail cell for the Container Insights column. */
function InsightsCell({ cluster }: { cluster: EKSCluster }) {
  const state = containerInsightsState(cluster);
  if (state === 'CONFIGURED') return <StatusMark state="healthy" label="Configured" />;
  if (state === 'NOT_CONFIGURED') return <StatusMark state="not-set-up" label="Not configured" />;
  return (
    <span title={unknownInsightsTitle(cluster)}>
      <StatusMark state="unknown" label="Unknown" />
    </span>
  );
}

const CloudK8sClusterTable: React.FC<CloudK8sClusterTableProps> = ({
  clusters,
  isLoading,
  onViewDetails,
  error = null,
  onRetry,
  onRescan,
  isRefreshing = false,
}) => {
  // A refresh over existing rows keeps the rows on screen and never falls back to the skeleton.
  const showSkeleton = isLoading && !isRefreshing;

  // After 8s of loading, tell the user the scan is still going rather than leave a silent skeleton.
  const [slowScan, setSlowScan] = useState(false);
  useEffect(() => {
    if (!showSkeleton) {
      setSlowScan(false);
      return undefined;
    }
    const timer = setTimeout(() => setSlowScan(true), 8000);
    return () => clearTimeout(timer);
  }, [showSkeleton]);

  const header = (count: number | null) => (
    <div className={`flex items-center justify-between gap-3 pb-3 ${HAIRLINE}`}>
      <h1 className={`text-[16px] font-semibold ${INK}`}>Clusters</h1>
      {count !== null && (
        <span className={`text-[12px] ${MUTED}`}>
          {count} cluster{count !== 1 ? 's' : ''}
        </span>
      )}
    </div>
  );

  // Loading: a skeleton, aria-busy, an sr-only line and NO count. Kept when refreshing is false.
  if (showSkeleton) {
    return (
      <section data-testid="fleet-loading" aria-busy="true">
        {header(null)}
        <span className="sr-only" role="status">Loading clusters…</span>
        <div className="space-y-2 pt-3">
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-11 rounded-[8px] bg-[rgba(0,0,0,0.04)] animate-pulse" aria-hidden="true" />
          ))}
        </div>
        {slowScan && (
          <p className={`pt-3 text-[12px] ${FAINT}`}>Scanning regions for clusters. This can take a little while.</p>
        )}
      </section>
    );
  }

  // Error / incomplete: an alert, kind-specific copy and a Retry that reloads. No count, no table.
  if (error) {
    const copy = errorCopy(error);
    return (
      <section>
        {header(null)}
        <div role="alert" data-testid="fleet-error" className="flex items-start gap-3 pt-4">
          <Icon icon="mdi:alert-octagon" width={18} aria-hidden="true" className={`mt-0.5 shrink-0 ${MUTED}`} />
          <div className="min-w-0 flex-1">
            <h2 className={`text-[14px] font-semibold ${INK}`}>{copy.title}</h2>
            <p className={`mt-0.5 text-[13px] ${MUTED}`}>{copy.detail}</p>
            {onRetry && (
              <button type="button" onClick={onRetry} data-testid="fleet-retry" className={`mt-3 inline-flex items-center gap-1.5 ${BUTTON}`}>
                <Icon icon="mdi:refresh" width={14} aria-hidden="true" />
                Retry
              </button>
            )}
          </div>
        </div>
      </section>
    );
  }

  // Ready with no clusters: the honest empty state (only when the scan is complete).
  if (clusters.length === 0) {
    return (
      <section>
        {header(0)}
        <CloudK8sNoClusters onRescan={onRescan} />
      </section>
    );
  }

  // Ready with clusters: the compact, de-boxed table.
  return (
    <section>
      {header(clusters.length)}
      <div className="overflow-x-auto">
        <table data-testid="fleet-table" className="w-full border-collapse">
          <thead>
            <tr className={HAIRLINE}>
              <th className={HEAD}>Cluster</th>
              <th className={`${HEAD} hidden md:table-cell`}>Region</th>
              <th className={HEAD}>Status</th>
              <th className={`${HEAD} hidden md:table-cell`}>Version</th>
              <th className={HEAD} title={CONTAINER_INSIGHTS_TOOLTIP}>Container Insights</th>
              <th className={`${HEAD} text-right`}>
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {clusters.map((cluster) => {
              const status = clusterStatusMark(cluster);
              return (
                <tr
                  key={`${cluster.name}-${cluster.region}`}
                  data-testid="fleet-row"
                  data-cluster-name={cluster.name}
                  className={`${HAIRLINE} last:border-b-0 hover:bg-[rgba(0,0,0,0.015)] transition-colors`}
                >
                  <td className={COL}>
                    <button
                      type="button"
                      onClick={() => onViewDetails(cluster)}
                      className={`inline-flex items-center gap-2 font-semibold ${INK} hover:underline underline-offset-2 outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)] rounded-[4px]`}
                    >
                      <Icon icon="mdi:kubernetes" width={16} color="#326CE5" aria-hidden="true" className="shrink-0" />
                      {cluster.name}
                    </button>
                  </td>
                  <td className={`${COL} hidden md:table-cell ${MUTED}`}>{cluster.region || '—'}</td>
                  <td className={COL}>
                    <StatusMark state={status.state} label={status.label} />
                  </td>
                  <td className={`${COL} hidden md:table-cell ${MUTED}`}>{cluster.version ? cluster.version : '—'}</td>
                  <td className={COL} data-container-insights={containerInsightsState(cluster)}>
                    <InsightsCell cluster={cluster} />
                  </td>
                  <td className={`${COL} text-right`}>
                    <button
                      type="button"
                      onClick={() => onViewDetails(cluster)}
                      aria-label={`Details for ${cluster.name}`}
                      className={`inline-flex items-center gap-1.5 ${BUTTON}`}
                    >
                      <Icon icon="mdi:open-in-new" width={12} aria-hidden="true" />
                      Details
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
};

export default CloudK8sClusterTable;
