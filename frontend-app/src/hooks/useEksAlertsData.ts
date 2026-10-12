/**
 * useEksAlertsData
 *
 * Fetches all EKS clusters for the account, then aggregates security/health
 * alerts across clusters for the SecOps Alerts → K8s tab.
 *
 * Alert sources extracted from each cluster dashboard:
 *  - Falco runtime alerts      → target: security tab
 *  - Kubescape compliance      → target: security tab
 *  - Trivy CVEs                → target: security tab
 *  - K8s Warning events        → target: overview tab
 *  - Node pressure / NotReady  → target: nodes tab
 *  - Failed / high-restart pods → target: pods tab
 *  - Karpenter issues          → target: karpenter tab
 */

import { useState, useCallback, useEffect, useRef } from 'react';
import apiClient from '../utils/apiClient';
import type { TabType } from '../types/eksTypes';

export type EksAlertSeverity = 'critical' | 'high' | 'medium' | 'low' | 'info';

export type EksAlertSource = 'falco' | 'kubescape' | 'trivy' | 'events' | 'nodes' | 'pods' | 'karpenter';

export interface EksAlertItem {
  id: string;
  clusterName: string;
  region: string;
  accountId: string;
  severity: EksAlertSeverity;
  source: EksAlertSource;
  targetTab: TabType;
  title: string;
  message: string;
  namespace?: string;
  count?: number;
  timestamp?: string;
}

interface ClusterRef {
  name: string;
  region: string;
  version?: string;
}

interface UseEksAlertsDataOptions {
  accountId: string | null;
}

interface UseEksAlertsDataReturn {
  alerts: EksAlertItem[];
  loading: boolean;
  error: string | null;
  counts: { critical: number; high: number; medium: number; total: number };
  refresh: () => void;
  /** Every EKS cluster in the account, including any beyond the ones scanned. */
  totalClusters: number;
  /** How many clusters were scanned; at most MAX_CLUSTERS. */
  scannedClusters: number;
  /** Clusters whose dashboard could not be loaded, so their alerts are unknown. */
  failedClusters: string[];
}

/** Dashboards are fetched from the browser, one request per cluster, so the fan-out is capped. */
const MAX_CLUSTERS = 10;

// ── severity normaliser ────────────────────────────────────────────────────
function normaliseSeverity(raw: string | undefined): EksAlertSeverity {
  const s = (raw || '').toLowerCase();
  if (s === 'critical' || s === '4' || s === 'emergency') return 'critical';
  if (s === 'high' || s === '3' || s === 'warning' || s === 'error') return 'high';
  if (s === 'medium' || s === '2' || s === 'notice') return 'medium';
  if (s === 'low' || s === '1' || s === 'informational') return 'low';
  return 'info';
}

// ── extract alerts from one cluster's dashboard ────────────────────────────
// Exported for the cluster page's Overview › Summary, so its top findings and the Alerts page agree
// (docs/cloudk8s/ui-revamp-design.md §10.1).
export function extractAlerts(
  clusterName: string,
  region: string,
  accountId: string,
  dashboard: any
): EksAlertItem[] {
  const alerts: EksAlertItem[] = [];

  // 1. Falco runtime security alerts
  const falcoAlerts: any[] = dashboard?.securityMetrics?.alerts || [];
  falcoAlerts.forEach((alert: any, i: number) => {
    const sev = normaliseSeverity(alert.priority);
    if (sev === 'info' || sev === 'low') return; // skip low-noise
    alerts.push({
      id: `${clusterName}-falco-${i}`,
      clusterName, region, accountId,
      severity: sev,
      source: 'falco',
      targetTab: 'security',
      title: alert.rule || alert.name || 'Runtime Alert',
      message: alert.description || `${alert.count || 1} event(s) detected`,
      count: alert.count,
      namespace: alert.namespace,
      timestamp: alert.lastTimestamp || alert.timestamp,
    });
  });

  // 2. Kubescape — surface critical/high vulnerability counts
  const ks = dashboard?.kubescape;
  if (ks) {
    if ((ks.totalCritical || 0) > 0) {
      alerts.push({
        id: `${clusterName}-ks-critical`,
        clusterName, region, accountId,
        severity: 'critical',
        source: 'kubescape',
        targetTab: 'security',
        title: `${ks.totalCritical} Critical Compliance Failures`,
        message: `Cluster compliance score: ${ks.complianceScore ?? 'N/A'}%. ${ks.totalCritical} critical control failures detected.`,
      });
    }
    if ((ks.totalHigh || 0) > 0) {
      alerts.push({
        id: `${clusterName}-ks-high`,
        clusterName, region, accountId,
        severity: 'high',
        source: 'kubescape',
        targetTab: 'security',
        title: `${ks.totalHigh} High Compliance Failures`,
        message: `${ks.totalHigh} high-severity control failures detected across namespaces.`,
      });
    }
  }

  // 3. Trivy image vulnerabilities
  const trivy = dashboard?.trivy;
  if (trivy) {
    if ((trivy.criticalCount || 0) > 0) {
      alerts.push({
        id: `${clusterName}-trivy-critical`,
        clusterName, region, accountId,
        severity: 'critical',
        source: 'trivy',
        targetTab: 'security',
        title: `${trivy.criticalCount} Critical CVEs in Container Images`,
        message: `${trivy.criticalCount} CRITICAL and ${trivy.highCount || 0} HIGH vulnerabilities found across ${trivy.totalImages || '?'} images.`,
      });
    } else if ((trivy.highCount || 0) > 0) {
      alerts.push({
        id: `${clusterName}-trivy-high`,
        clusterName, region, accountId,
        severity: 'high',
        source: 'trivy',
        targetTab: 'security',
        title: `${trivy.highCount} High CVEs in Container Images`,
        message: `${trivy.highCount} HIGH severity vulnerabilities found in container images.`,
      });
    }
  }

  // 4. K8s Warning events — surface each unique warning reason
  const warningEvents: any[] = (dashboard?.k8sEvents || []).filter((e: any) => e.type === 'Warning');
  const eventsByReason = new Map<string, any[]>();
  warningEvents.forEach((e: any) => {
    const key = e.reason || 'Warning';
    if (!eventsByReason.has(key)) eventsByReason.set(key, []);
    eventsByReason.get(key)!.push(e);
  });
  eventsByReason.forEach((events, reason) => {
    const totalCount = events.reduce((s, e) => s + (e.count || 1), 0);
    const sample = events[0];
    const objName = sample?.involvedObject?.name || sample?.name || '';
    const ns = sample?.involvedObject?.namespace || sample?.namespace || '';
    alerts.push({
      id: `${clusterName}-event-${reason}`,
      clusterName, region, accountId,
      severity: 'high',
      source: 'events',
      targetTab: 'overview',
      title: reason,
      message: objName
        ? `${objName}${ns ? ` (${ns})` : ''}: ${sample?.message || reason}`
        : sample?.message || reason,
      count: totalCount,
      namespace: ns,
      timestamp: sample?.lastTimestamp || sample?.eventTime,
    });
  });

  // 5. Node issues — DiskPressure, MemoryPressure, NotReady
  const nodeMetricNodes: any[] = dashboard?.nodeMetrics?.nodes || [];
  const resUtilNodes: any[] = dashboard?.resourceUtilization?.nodes || [];
  const allNodes = nodeMetricNodes.length > 0 ? nodeMetricNodes : resUtilNodes;

  const notReadyNodes = allNodes.filter((n: any) =>
    n.ready === false || n.condition === 'NotReady' || n.condition === 'notReady'
  );
  if (notReadyNodes.length > 0) {
    alerts.push({
      id: `${clusterName}-node-notready`,
      clusterName, region, accountId,
      severity: 'critical',
      source: 'nodes',
      targetTab: 'nodes',
      title: `${notReadyNodes.length} Node(s) Not Ready`,
      message: notReadyNodes.map((n: any) => n.name || n.nodeName).filter(Boolean).join(', ') || 'Check node health',
    });
  }

  const diskPressureNodes = nodeMetricNodes.filter((n: any) => n.diskPressure);
  if (diskPressureNodes.length > 0) {
    alerts.push({
      id: `${clusterName}-node-disk`,
      clusterName, region, accountId,
      severity: 'high',
      source: 'nodes',
      targetTab: 'nodes',
      title: `${diskPressureNodes.length} Node(s) Under Disk Pressure`,
      message: diskPressureNodes.map((n: any) => n.name || n.nodeName).filter(Boolean).join(', '),
    });
  }

  const memPressureNodes = nodeMetricNodes.filter((n: any) => n.memoryPressure);
  if (memPressureNodes.length > 0) {
    alerts.push({
      id: `${clusterName}-node-mem`,
      clusterName, region, accountId,
      severity: 'high',
      source: 'nodes',
      targetTab: 'nodes',
      title: `${memPressureNodes.length} Node(s) Under Memory Pressure`,
      message: memPressureNodes.map((n: any) => n.name || n.nodeName).filter(Boolean).join(', '),
    });
  }

  // 6. Failed pods and high-restart pods
  const allPods: any[] = (dashboard?.k8sPods?.length ? dashboard.k8sPods : dashboard?.workloadHealth?.pods) || [];

  const failedPods = allPods.filter((p: any) => p.phase === 'Failed');
  if (failedPods.length > 0) {
    alerts.push({
      id: `${clusterName}-pods-failed`,
      clusterName, region, accountId,
      severity: 'high',
      source: 'pods',
      targetTab: 'pods',
      title: `${failedPods.length} Failed Pod(s)`,
      message: failedPods.slice(0, 3).map((p: any) => p.name).join(', ') + (failedPods.length > 3 ? ` +${failedPods.length - 3} more` : ''),
    });
  }

  const highRestartPods = allPods.filter((p: any) => (p.restarts || 0) > 5);
  if (highRestartPods.length > 0) {
    alerts.push({
      id: `${clusterName}-pods-restarts`,
      clusterName, region, accountId,
      severity: 'medium',
      source: 'pods',
      targetTab: 'pods',
      title: `${highRestartPods.length} Pod(s) With High Restart Count`,
      message: highRestartPods.slice(0, 3).map((p: any) => `${p.name} (${p.restarts}x)`).join(', ') + (highRestartPods.length > 3 ? ` +${highRestartPods.length - 3} more` : ''),
    });
  }

  // 7. Karpenter issues
  const karpenter = dashboard?.karpenter;
  if (karpenter && !karpenter.healthy) {
    alerts.push({
      id: `${clusterName}-karpenter-unhealthy`,
      clusterName, region, accountId,
      severity: 'high',
      source: 'karpenter',
      targetTab: 'karpenter',
      title: 'Karpenter Autoscaler Unhealthy',
      message: 'Karpenter controller is offline or unable to provision. Check NodePool and EC2NodeClass configuration.',
    });
  }

  return alerts;
}

// ── sort order ─────────────────────────────────────────────────────────────
export const SEV_ORDER: Record<EksAlertSeverity, number> = { critical: 0, high: 1, medium: 2, low: 3, info: 4 };

export const useEksAlertsData = ({ accountId }: UseEksAlertsDataOptions): UseEksAlertsDataReturn => {
  const [alerts, setAlerts] = useState<EksAlertItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const [totalClusters, setTotalClusters] = useState(0);
  const [scannedClusters, setScannedClusters] = useState(0);
  const [failedClusters, setFailedClusters] = useState<string[]>([]);

  // Set by the Refresh button only, so opening the tab still reads the dashboards' caches. The
  // button used to re-request with forceRefresh=false, which returned the same cached data.
  const forceNextRef = useRef(false);
  const refresh = useCallback(() => {
    forceNextRef.current = true;
    setTick(t => t + 1);
  }, []);

  useEffect(() => {
    if (!accountId) return;

    let cancelled = false;
    const forceRefresh = forceNextRef.current;
    forceNextRef.current = false;

    const run = async () => {
      setLoading(true);
      setError(null);
      try {
        // 1. Fetch EKS cluster list
        const clusters: any[] = await apiClient.get(
          `/api/xamops/k8s/clusters?accountIds=${accountId}&forceRefresh=false`
        );

        if (cancelled) return;

        const clusterRefs: ClusterRef[] = (clusters || []).map((c: any) => ({
          name: c.clusterName || c.name,
          region: c.region || 'us-east-1',
          version: c.version || '1.33',
        })).filter((c: ClusterRef) => !!c.name);

        // 2. Fetch dashboards in parallel (capped)
        const scanned = clusterRefs.slice(0, MAX_CLUSTERS);
        const results = await Promise.allSettled(
          scanned.map(c =>
            apiClient.get<any>(
              `/api/xamops/eks/${c.name}/dashboard?accountId=${accountId}&region=${c.region}&version=${c.version || '1.33'}&forceRefresh=${forceRefresh}`
            ).then(dashboard => ({ cluster: c, dashboard }))
          )
        );

        if (cancelled) return;

        const all: EksAlertItem[] = [];
        // A cluster whose dashboard failed contributes no rows, which looked exactly like a
        // healthy cluster. It is recorded so the tab can say its alerts are unknown.
        const failed: string[] = [];
        results.forEach((result, i) => {
          if (result.status === 'fulfilled') {
            const { cluster, dashboard } = result.value;
            const extracted = extractAlerts(cluster.name, cluster.region, accountId, dashboard);
            all.push(...extracted);
          } else {
            failed.push(scanned[i].name);
          }
        });
        setTotalClusters(clusterRefs.length);
        setScannedClusters(scanned.length);
        setFailedClusters(failed);

        // Sort by severity then by cluster name
        all.sort((a, b) => {
          const sd = SEV_ORDER[a.severity] - SEV_ORDER[b.severity];
          if (sd !== 0) return sd;
          return a.clusterName.localeCompare(b.clusterName);
        });

        setAlerts(all);
      } catch (err: any) {
        if (!cancelled) setError(err?.message || 'Failed to load EKS alerts');
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    run();
    return () => { cancelled = true; };
  }, [accountId, tick]);

  const counts = {
    critical: alerts.filter(a => a.severity === 'critical').length,
    high: alerts.filter(a => a.severity === 'high').length,
    medium: alerts.filter(a => a.severity === 'medium').length,
    total: alerts.length,
  };

  return { alerts, loading, error, counts, refresh, totalClusters, scannedClusters, failedClusters };
};
