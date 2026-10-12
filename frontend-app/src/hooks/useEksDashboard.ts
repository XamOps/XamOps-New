/**
 * useEksDashboard Hook
 *
 * Custom hook for EKS cluster dashboard data fetching and state management
 * Converted from: eks-details.html Alpine.js logic
 */

import { useState, useCallback, useEffect, useMemo } from 'react';
import apiClient from '../utils/apiClient';

// Parse Kubernetes CPU quantity strings → millicores
// Examples: "10m" → 10, "100m" → 100, "1" → 1000, "1.5" → 1500
function parseCpuMillicores(v: any): number {
  if (v === null || v === undefined || v === '') return 0;
  const s = String(v).trim();
  if (s.endsWith('m')) return parseFloat(s) || 0;
  const n = parseFloat(s);
  return isNaN(n) ? 0 : Math.round(n * 1000);
}

// Parse Kubernetes memory quantity strings → MiB (mebibytes)
// Examples: "32Mi" → 32, "3400576Ki" → ~3321, "1Gi" → 1024, "134217728" → ~128
function parseMemoryMiB(v: any): number {
  if (v === null || v === undefined || v === '') return 0;
  const s = String(v).trim();
  if (s.endsWith('Ki')) return parseFloat(s) / 1024;
  if (s.endsWith('Mi')) return parseFloat(s);
  if (s.endsWith('Gi')) return parseFloat(s) * 1024;
  if (s.endsWith('Ti')) return parseFloat(s) * 1024 * 1024;
  if (s.endsWith('K') || s.endsWith('k')) return parseFloat(s) / 1024;
  if (s.endsWith('M')) return parseFloat(s);
  if (s.endsWith('G')) return parseFloat(s) * 1024;
  const bytes = parseFloat(s);
  return isNaN(bytes) ? 0 : bytes / (1024 * 1024);
}
import {
  EksDashboard,
  ContainerWithMetrics,
  ContainerStatus,
  PotentialSavings,
  QuickWin,
  KarpenterStatus,
  NodeCost
} from '../types/eksTypes';
import { useEksDashboardCache } from '../contexts/EksDashboardCacheContext';

interface UseEksDashboardParams {
  clusterName: string | null;
  region: string;
  /** The link's version; left out of the request when null, so no version is guessed. */
  version: string | null;
  accountId: string | null;
}

/** The `&version=` query part, or nothing when the version isn't known. */
const versionParam = (version: string | null) => (version ? `&version=${encodeURIComponent(version)}` : '');

export interface UseEksDashboardReturn {
  dashboard: EksDashboard | null;
  loading: boolean;
  isRefreshing: boolean;
  isSyncing: boolean;
  /** The last sync stopped polling before new data arrived; reset when the next sync starts. */
  syncTimedOut: boolean;
  error: string | null;
  lastUpdateTimestamp: number;
  containerSearchQuery: string;
  setContainerSearchQuery: (query: string) => void;
  containerNamespaceFilter: string;
  setContainerNamespaceFilter: (ns: string) => void;
  fetchDashboard: (forceRefresh?: boolean) => Promise<void>;
  refresh: () => Promise<void>;
  syncInBackground: () => Promise<void>;
  // Computed values
  clusterHealth: string;
  cpuColor: string;
  memoryColor: string;
  costEfficiency: number;
  idleCostPercentage: number;
  totalPodsCount: number;
  sortedNodesByCost: NodeCost[];
  sortedNodesByPressure: any[];
  clusterHealthScore: number;
  resourceScore: number;
  costScore: number;
  securityScore: number;
  healthScoreLabel: string;
  healthScoreColor: string;
  potentialSavings: PotentialSavings;
  quickWins: QuickWin[];
  securityStatus: string;
  sortedAlertsByPriority: any[];
  karpenterOverallStatus: KarpenterStatus;
  karpenterProvisioningStatus: { canProvision: boolean; reason: string };
  totalContainersCount: number;
  totalContainerRestarts: number;
  allContainersWithMetrics: ContainerWithMetrics[];
  filteredContainers: ContainerWithMetrics[];
  uniqueNamespaces: string[];
  // Derived / computed data
  normalizedPods: any[];
  derivedNodeRequestMetrics: Record<string, {
    cpuRequestsMillicores: number;
    memoryRequestsMiB: number;
    cpuRequestsPct: number;
    memoryRequestsPct: number;
    allocatableCpuMillicores: number;
    allocatableMemoryMiB: number;
  }>;
  derivedPodResourceMetrics: {
    totalCpuRequests: number;
    totalCpuLimits: number;
    totalCpuCapacity: number;
    totalCpuUsage: number;
    totalMemoryRequests: number;
    totalMemoryLimits: number;
    totalMemoryCapacity: number;
    totalMemoryUsage: number;
    podsWithoutRequests: number;
    podsWithoutLimits: number;
    _derived: boolean;
  } | null;
  // Helper functions
  getPodsCountByPhase: (phase: string) => number;
  getPodsCountByQoS: (qosClass: string) => number;
  getNodeConditionCount: (condition: string) => number;
  getContainersCountByState: (state: string) => number;
  getTimeSince: (timestamp: number) => string;
}

export const useEksDashboard = ({
  clusterName,
  region,
  version,
  accountId,
}: UseEksDashboardParams): UseEksDashboardReturn => {
  // Frontend cache context - persists data across navigation
  const dashboardCache = useEksDashboardCache();

  const [dashboard, setDashboard] = useState<EksDashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [isSyncing, setIsSyncing] = useState(false);
  const [syncTimedOut, setSyncTimedOut] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastUpdateTimestamp, setLastUpdateTimestamp] = useState(Date.now());
  const [containerSearchQuery, setContainerSearchQuery] = useState('');
  const [containerNamespaceFilter, setContainerNamespaceFilter] = useState('');

  /**
   * Fetch dashboard data from API
   * Uses frontend cache to avoid re-fetching on navigation
   */
  const fetchDashboard = useCallback(async (forceRefresh = false) => {
    if (!accountId || !clusterName) {
      setError(`Missing required parameters: ${!accountId ? 'accountId ' : ''}${!clusterName ? 'clusterName' : ''}`);
      setLoading(false);
      return;
    }

    // Check frontend cache first (for navigation scenarios)
    // Skip cache if forceRefresh is true
    if (!forceRefresh && dashboardCache.isCacheValid(clusterName, accountId, region)) {
      const cached = dashboardCache.getCachedDashboard(clusterName, accountId, region);
      if (cached) {
        console.log('Using frontend cached dashboard data');
        setDashboard(cached.data);
        setLastUpdateTimestamp(cached.lastSyncedAt);
        setLoading(false);
        return;
      }
    }

    if (!forceRefresh) setLoading(true);
    if (forceRefresh) setIsRefreshing(true);
    setError(null);

    try {
      const data = await apiClient.get<EksDashboard>(
        `/api/xamops/eks/${clusterName}/dashboard?accountId=${accountId}&region=${region}${versionParam(version)}&forceRefresh=${forceRefresh}`
      );

      setDashboard(data);

      // Use server's lastSyncedAt if available, otherwise use current time
      const syncTimestamp = data.lastSyncedAt
        ? new Date(data.lastSyncedAt).getTime()
        : Date.now();

      setLastUpdateTimestamp(syncTimestamp);

      // Update frontend cache
      dashboardCache.setCachedDashboard(clusterName, accountId, region, data, syncTimestamp);

      // Store metric snapshot for trends
      storeMetricSnapshot(clusterName, data);
    } catch (err: any) {
      console.error('Error fetching dashboard:', err);
      setError(err.message);
    } finally {
      setLoading(false);
      setIsRefreshing(false);
    }
  }, [accountId, clusterName, region, version, dashboardCache]);

  /**
   * Manual refresh (forces fresh data) - blocks until complete
   */
  const refresh = useCallback(async () => {
    if (isRefreshing) return;
    await fetchDashboard(true);
  }, [fetchDashboard, isRefreshing]);

  /**
   * Sync data in background - shows current data while fetching new data
   * Triggers backend sync and polls for updated data
   */
  const syncInBackground = useCallback(async () => {
    if (!accountId || !clusterName || isSyncing) return;

    setIsSyncing(true);
    setSyncTimedOut(false);
    const previousSyncTime = lastUpdateTimestamp;

    try {
      // Trigger background sync on backend
      await apiClient.post(
        `/api/xamops/eks/${clusterName}/sync?accountId=${accountId}&region=${region}`
      );

      // Poll for updated data (check every 3 seconds, max 2 minutes)
      const maxAttempts = 40;
      let attempts = 0;

      const pollForUpdate = async (): Promise<boolean> => {
        try {
          const data = await apiClient.get<EksDashboard>(
            `/api/xamops/eks/${clusterName}/dashboard?accountId=${accountId}&region=${region}${versionParam(version)}&forceRefresh=false`
          );

          if (data.lastSyncedAt) {
            const newSyncTime = new Date(data.lastSyncedAt).getTime();
            if (newSyncTime > previousSyncTime) {
              // New data available!
              setDashboard(data);
              setLastUpdateTimestamp(newSyncTime);
              // Update frontend cache with new data
              dashboardCache.setCachedDashboard(clusterName, accountId, region, data, newSyncTime);
              storeMetricSnapshot(clusterName, data);
              return true;
            }
          }
          return false;
        } catch {
          return false;
        }
      };

      // Poll until new data arrives or timeout
      let updated = false;
      while (attempts < maxAttempts) {
        await new Promise(resolve => setTimeout(resolve, 3000));
        attempts++;

        updated = await pollForUpdate();
        if (updated) {
          console.log(`Sync completed after ${attempts * 3} seconds`);
          break;
        }
      }

      // The backend may still be syncing; the page says so instead of failing silently.
      if (!updated) setSyncTimedOut(true);

    } catch (err: any) {
      console.error('Error triggering background sync:', err);
      setError(err.message);
    } finally {
      setIsSyncing(false);
    }
  }, [accountId, clusterName, region, version, isSyncing, lastUpdateTimestamp, dashboardCache]);

  /**
   * Store metric snapshot for trends
   */
  const storeMetricSnapshot = (cluster: string, data: EksDashboard) => {
    if (!data) return;

    const snapshot = {
      timestamp: Date.now(),
      cpu: data.clusterOverview?.cpuUsagePercent || 0,
      memory: data.clusterOverview?.memoryUsagePercent || 0,
      cost: data.costMetrics?.dailyCost || 0,
      securityEvents: data.securityMetrics?.totalEvents || 0
    };

    try {
      let history = JSON.parse(localStorage.getItem(`clusterHistory_${cluster}`) || '[]');
      history.push(snapshot);

      if (history.length > 288) {
        history = history.slice(-288);
      }

      localStorage.setItem(`clusterHistory_${cluster}`, JSON.stringify(history));
    } catch (e) {
      console.warn('Failed to store metric snapshot:', e);
    }
  };

  // Computed values
  const clusterHealth = useMemo(() => {
    if (!dashboard) return 'healthy';

    const cpu = dashboard.clusterOverview?.cpuUsagePercent || 0;
    const memory = dashboard.clusterOverview?.memoryUsagePercent || 0;
    const events = dashboard.securityMetrics?.eventsByPriority || {};
    const criticalAlerts = events['CRITICAL'] || events['high'] || events['4'] || 0;

    if (cpu >= 85 || memory >= 85 || criticalAlerts > 0) return 'at-risk';
    if (cpu >= 70 || memory >= 70) return 'degraded';
    return 'healthy';
  }, [dashboard]);

  const cpuColor = useMemo(() => {
    const cpu = dashboard?.clusterOverview?.cpuUsagePercent || 0;
    if (cpu >= 85) return '#dc2626';
    if (cpu >= 70) return '#f59e0b';
    return '#10b981';
  }, [dashboard]);

  const memoryColor = useMemo(() => {
    const memory = dashboard?.clusterOverview?.memoryUsagePercent || 0;
    if (memory >= 85) return '#dc2626';
    if (memory >= 70) return '#f59e0b';
    return '#10b981';
  }, [dashboard]);

  const costEfficiency = useMemo(() => {
    const cpu = dashboard?.clusterOverview?.cpuUsagePercent || 0;
    const memory = dashboard?.clusterOverview?.memoryUsagePercent || 0;
    return Math.round((cpu + memory) / 2);
  }, [dashboard]);

  const idleCostPercentage = useMemo(() => {
    return Math.max(0, 100 - costEfficiency);
  }, [costEfficiency]);

  const totalPodsCount = useMemo(() => {
    // prefer k8sPods (from k8s-resources collector) over workloadHealth.pods (from prometheus)
    return dashboard?.k8sPods?.length || dashboard?.workloadHealth?.pods?.length || 0;
  }, [dashboard]);

  const sortedNodesByCost = useMemo(() => {
    const costNodes = dashboard?.costMetrics?.nodeCosts || [];
    const resourceNodes = dashboard?.resourceUtilization?.nodes || [];

    const resourceIndex = resourceNodes.reduce((acc: Record<string, any>, node) => {
      acc[node.nodeName] = node;
      return acc;
    }, {});

    return costNodes
      .map(node => ({
        ...node,
        cpuUsagePercent: node.cpuUsagePercent || resourceIndex[node.nodeName]?.cpuUsagePercent || 0
      }))
      .sort((a, b) => (b.hourlyRate || 0) - (a.hourlyRate || 0));
  }, [dashboard]);

  const sortedNodesByPressure = useMemo(() => {
    const resUtilNodes = dashboard?.resourceUtilization?.nodes || [];
    if (resUtilNodes.length > 0) {
      return [...resUtilNodes].sort((a, b) => {
        const pressureA = Math.max(a.cpuUsagePercent || 0, a.memoryUsagePercent || 0);
        const pressureB = Math.max(b.cpuUsagePercent || 0, b.memoryUsagePercent || 0);
        return pressureB - pressureA;
      });
    }
    // Fallback: k8s-resources collector sends nodes into nodeMetrics.nodes with
    // conditions[] array but no cpuUsagePercent — normalise shape so the Nodes tab renders
    const nodeMetricNodes: any[] = dashboard?.nodeMetrics?.nodes || [];
    return nodeMetricNodes.map((n: any) => {
      const readyCondition = n.conditions?.find((c: any) => c.type === 'Ready');
      const diskPressureCondition = n.conditions?.find((c: any) => c.type === 'DiskPressure');
      const memPressureCondition = n.conditions?.find((c: any) => c.type === 'MemoryPressure');
      const normName = n.name || n.nodeName;
      return {
        // spread original first, then override with computed/normalised values
        ...n,
        nodeName: normName,
        name: normName,
        cpuUsagePercent: n.cpuUsagePercent || 0,
        memoryUsagePercent: n.memoryUsagePercent || 0,
        cpuUsageMillicores: n.cpuUsageMillicores || 0,
        memoryUsageMB: n.memoryUsageMB || 0,
        // ready / condition computed from conditions[]
        ready: readyCondition?.status === 'True',
        condition: readyCondition?.status === 'True' ? 'Ready' : 'NotReady',
        diskPressure: diskPressureCondition?.status === 'True',
        memoryPressure: memPressureCondition?.status === 'True',
      };
    });
  }, [dashboard]);

  // Health score calculations
  const resourceScore = useMemo(() => {
    const cpu = dashboard?.clusterOverview?.cpuUsagePercent || 0;
    const mem = dashboard?.clusterOverview?.memoryUsagePercent || 0;
    const avgUtil = (cpu + mem) / 2;

    let score;
    if (avgUtil >= 40 && avgUtil <= 75) score = 100;
    else if (avgUtil < 40) score = Math.max(0, 100 - (40 - avgUtil) * 2);
    else score = Math.max(0, 100 - (avgUtil - 75) * 3);

    return Math.round(score);
  }, [dashboard]);

  const costScore = useMemo(() => costEfficiency, [costEfficiency]);

  const securityScore = useMemo(() => {
    const critical = dashboard?.securityMetrics?.eventsByPriority?.['4'] ||
      dashboard?.securityMetrics?.eventsByPriority?.['CRITICAL'] || 0;
    const high = dashboard?.securityMetrics?.eventsByPriority?.['3'] ||
      dashboard?.securityMetrics?.eventsByPriority?.['WARNING'] || 0;

    let score = 100;
    score -= critical * 10;
    score -= high * 5;

    return Math.max(0, Math.round(score));
  }, [dashboard]);

  const clusterHealthScore = useMemo(() => {
    const weights = { resource: 0.4, cost: 0.3, security: 0.3 };
    return Math.round(
      resourceScore * weights.resource +
      costScore * weights.cost +
      securityScore * weights.security
    );
  }, [resourceScore, costScore, securityScore]);

  const healthScoreLabel = useMemo(() => {
    if (clusterHealthScore >= 80) return 'Excellent Health';
    if (clusterHealthScore >= 60) return 'Good Health';
    if (clusterHealthScore >= 40) return 'Fair Health';
    return 'Needs Attention';
  }, [clusterHealthScore]);

  const healthScoreColor = useMemo(() => {
    if (clusterHealthScore >= 80) return '#10b981';
    if (clusterHealthScore >= 60) return '#3b82f6';
    if (clusterHealthScore >= 40) return '#f59e0b';
    return '#dc2626';
  }, [clusterHealthScore]);

  const potentialSavings = useMemo((): PotentialSavings => {
    const nodeCosts = dashboard?.costMetrics?.nodeCosts || [];
    const underutilizedNodes = nodeCosts.filter(node => (node.cpuUsagePercent || 0) < 40);

    const wastedCost = underutilizedNodes.reduce((sum, node) => {
      const wastePercent = 100 - (node.cpuUsagePercent || 0);
      return sum + (node.dailyCost * (wastePercent / 100));
    }, 0);

    return {
      daily: wastedCost,
      monthly: wastedCost * 30,
      affectedNodes: underutilizedNodes.length
    };
  }, [dashboard]);

  const quickWins = useMemo((): QuickWin[] => {
    const wins: QuickWin[] = [];

    const criticalVulns = dashboard?.kubescape?.totalCritical || 0;
    if (criticalVulns > 0) {
      wins.push({
        priority: 1,
        type: 'security',
        icon: 'fa-shield-alt',
        title: `Fix ${criticalVulns} Critical Security Vulnerabilities`,
        impact: 'Prevent potential exploits',
        actions: ['View Details', 'Auto-Fix Available']
      });
    }

    if (potentialSavings.affectedNodes > 0) {
      wins.push({
        priority: 2,
        type: 'cost',
        icon: 'fa-bolt',
        title: `Optimize ${potentialSavings.affectedNodes} Underutilized Nodes`,
        impact: `Save $${potentialSavings.daily.toFixed(2)}/day`,
        actions: ['View Nodes', 'Apply Recommendations']
      });
    }

    if (!dashboard?.karpenter?.healthy) {
      wins.push({
        priority: 3,
        type: 'optimization',
        icon: 'fa-rocket',
        title: 'Enable Karpenter Autoscaling',
        impact: `Save ~$${(potentialSavings.daily * 0.6).toFixed(2)}/day with intelligent scaling`,
        actions: ['Configure Now', 'Learn More']
      });
    }

    return wins.sort((a, b) => a.priority - b.priority);
  }, [dashboard, potentialSavings]);

  const securityStatus = useMemo(() => {
    let status = 'safe';

    if (dashboard?.securityMetrics?.eventsByPriority) {
      const events = dashboard.securityMetrics.eventsByPriority;
      const critical = events['CRITICAL'] || events['4'] || events['high'] || 0;
      const warning = events['WARNING'] || events['3'] || events['medium'] || 0;

      if (critical > 0) status = 'critical';
      else if (warning > 3) status = 'warning';
    }

    if (dashboard?.kubescape) {
      const k = dashboard.kubescape;
      if ((k.totalCritical || 0) > 0 || (k.totalHigh || 0) > 0) {
        status = 'critical';
      } else if (status === 'safe' && (k.totalMedium || 0) > 5) {
        status = 'warning';
      }
    }

    return status;
  }, [dashboard]);

  const sortedAlertsByPriority = useMemo(() => {
    const alerts = dashboard?.securityMetrics?.alerts || [];
    const priorityOrder: Record<string, number> = { 'CRITICAL': 0, '4': 0, 'WARNING': 1, '3': 1, 'INFO': 2, '2': 2, '1': 2 };
    return [...alerts].sort((a, b) => {
      const pA = priorityOrder[a.priority] !== undefined ? priorityOrder[a.priority] : 99;
      const pB = priorityOrder[b.priority] !== undefined ? priorityOrder[b.priority] : 99;
      if (pA !== pB) return pA - pB;
      return (b.count || 0) - (a.count || 0);
    });
  }, [dashboard]);

  const karpenterProvisioningStatus = useMemo(() => {
    const nodeClasses = dashboard?.karpenter?.ec2NodeClasses || [];

    if (nodeClasses.length === 0) {
      return { canProvision: false, reason: 'No EC2NodeClasses configured' };
    }

    const readyNodeClasses = nodeClasses.filter(nc => nc.ready);

    if (readyNodeClasses.length === 0) {
      const firstNodeClass = nodeClasses[0];
      const reason = firstNodeClass.readyMessage || 'NodeClass not ready (AMI resolution failed)';
      return { canProvision: false, reason };
    }

    return { canProvision: true, reason: `${readyNodeClasses.length} NodeClass(es) ready` };
  }, [dashboard]);

  const karpenterOverallStatus = useMemo((): KarpenterStatus => {
    const controllerHealthy = dashboard?.karpenter?.healthy || false;
    const provisioning = karpenterProvisioningStatus;

    if (controllerHealthy && provisioning.canProvision) {
      return { status: 'healthy', label: 'Healthy', color: 'green' };
    } else if (controllerHealthy && !provisioning.canProvision) {
      return { status: 'degraded', label: 'Degraded (Provisioning Blocked)', color: 'amber' };
    } else {
      return { status: 'unhealthy', label: 'Unhealthy', color: 'red' };
    }
  }, [dashboard, karpenterProvisioningStatus]);

  // Container-related computed values
  const totalContainersCount = useMemo(() => {
    const pods = dashboard?.workloadHealth?.pods || [];
    return pods.reduce((count, pod) => {
      return count + ((pod.containers || pod.containerStatuses)?.length || 0);
    }, 0);
  }, [dashboard]);

  const totalContainerRestarts = useMemo(() => {
    const pods = dashboard?.workloadHealth?.pods || [];
    return pods.reduce((total, pod) => {
      const ctrs = pod.containers || pod.containerStatuses;
      if (!ctrs) return total;
      return total + ctrs.reduce((sum, c) => sum + (c.restartCount || 0), 0);
    }, 0);
  }, [dashboard]);

  const allContainersWithMetrics = useMemo((): ContainerWithMetrics[] => {
    // prefer k8sPods (from k8s-resources collector) over workloadHealth.pods (from prometheus)
    const wlPods = dashboard?.workloadHealth?.pods || [];
    const pods = (dashboard?.k8sPods?.length ? dashboard.k8sPods : wlPods);
    const containers: ContainerWithMetrics[] = [];

    const metricsMap: Record<string, any> = {};
    const containerMetrics = dashboard?.containerMetrics?.containers || [];
    containerMetrics.forEach(metric => {
      const key = `${metric.pod}/${metric.name}`;
      metricsMap[key] = metric;
    });

    pods.forEach(pod => {
      const podContainers = pod.containers || pod.containerStatuses;
      if (!podContainers) return;

      podContainers.forEach((containerStatus: ContainerStatus) => {
        const key = `${pod.name}/${containerStatus.name}`;
        const metrics = metricsMap[key] || {};

        containers.push({
          id: key,
          name: containerStatus.name,
          pod: pod.name,
          namespace: pod.namespace,
          image: containerStatus.image || 'N/A',
          state: containerStatus.state,
          ready: containerStatus.ready,
          restartCount: containerStatus.restartCount || 0,
          cpuUsage: metrics.cpuUsage || 0,
          memoryUsage: metrics.memoryUsage || 0,
          fsUsage: metrics.fsUsage || 0
        });
      });
    });

    return containers;
  }, [dashboard]);

  const uniqueNamespaces = useMemo(() => {
    const wlPods = dashboard?.workloadHealth?.pods || [];
    const pods = (dashboard?.k8sPods?.length ? dashboard.k8sPods : wlPods);
    const namespaces = new Set<string>();
    pods.forEach((pod: any) => {
      if (pod.namespace) namespaces.add(pod.namespace);
    });
    return Array.from(namespaces).sort();
  }, [dashboard]);

  const filteredContainers = useMemo(() => {
    let containers = allContainersWithMetrics;

    if (containerNamespaceFilter) {
      containers = containers.filter(c => c.namespace === containerNamespaceFilter);
    }

    if (containerSearchQuery) {
      const query = containerSearchQuery.toLowerCase();
      containers = containers.filter(c =>
        (c.name && c.name.toLowerCase().includes(query)) ||
        (c.pod && c.pod.toLowerCase().includes(query)) ||
        (c.image && c.image.toLowerCase().includes(query))
      );
    }

    return containers;
  }, [allContainersWithMetrics, containerNamespaceFilter, containerSearchQuery]);

  // Normalise every pod: aggregate container-level cpuRequest/cpuLimit/memoryRequest/memoryLimit
  // to pod level (as numbers), and add top-level `restarts` from containers[].restartCount.
  // The k8s-resources collector stores these inside containers[] as strings ("10m", "32Mi").
  const normalizedPods = useMemo(() => {
    const rawPods: any[] = dashboard?.k8sPods?.length
      ? dashboard.k8sPods
      : (dashboard?.workloadHealth?.pods || []);
    return rawPods.map((pod: any) => {
      const containers: any[] = pod.containers || pod.containerStatuses || [];
      const cpuReq  = containers.reduce((s: number, c: any) => s + parseCpuMillicores(c.cpuRequest),  0);
      const memReq  = containers.reduce((s: number, c: any) => s + parseMemoryMiB(c.memoryRequest),   0);
      const cpuLim  = containers.reduce((s: number, c: any) => s + parseCpuMillicores(c.cpuLimit),    0);
      const memLim  = containers.reduce((s: number, c: any) => s + parseMemoryMiB(c.memoryLimit),     0);
      const restarts = containers.reduce((s: number, c: any) => s + (c.restartCount || 0), 0);
      return {
        ...pod,
        restarts:       pod.restarts != null ? pod.restarts : restarts,
        cpuRequest:     cpuReq,
        memoryRequest:  memReq,
        cpuLimit:       parseCpuMillicores(pod.cpuLimit)  || cpuLim,
        memoryLimit:    parseMemoryMiB(pod.memoryLimit)   || memLim,
      };
    });
  }, [dashboard]);

  // Per-node CPU/Memory request percentages — derived by summing pod container requests
  // per nodeName and dividing by node allocatableCpu/allocatableMemory.
  // Populates the "CPU Requests" / "Memory Requests" bars which the backend leaves as N/A.
  const derivedNodeRequestMetrics = useMemo(() => {
    if (normalizedPods.length === 0 || sortedNodesByPressure.length === 0) return {};
    const perNode: Record<string, { cpu: number; mem: number }> = {};
    normalizedPods.forEach((pod: any) => {
      const key = pod.nodeName || pod.node;
      if (!key) return;
      if (!perNode[key]) perNode[key] = { cpu: 0, mem: 0 };
      perNode[key].cpu += pod.cpuRequest || 0;
      perNode[key].mem += pod.memoryRequest || 0;
    });
    const result: Record<string, any> = {};
    sortedNodesByPressure.forEach((node: any) => {
      const name = node.nodeName || node.name;
      const reqs = perNode[name] || { cpu: 0, mem: 0 };
      const allocCpu = parseCpuMillicores(node.allocatableCpu ?? node.cpuAllocatable);
      const allocMem = parseMemoryMiB(node.allocatableMemory ?? node.memoryAllocatable);
      result[name] = {
        cpuRequestsMillicores: Math.round(reqs.cpu),
        memoryRequestsMiB:     Math.round(reqs.mem),
        cpuRequestsPct:   allocCpu > 0 ? Math.min(100, (reqs.cpu / allocCpu) * 100) : 0,
        memoryRequestsPct: allocMem > 0 ? Math.min(100, (reqs.mem / allocMem) * 100) : 0,
        allocatableCpuMillicores: allocCpu,
        allocatableMemoryMiB:    allocMem,
      };
    });
    return result;
  }, [normalizedPods, sortedNodesByPressure]);

  // Compute podResourceMetrics from container requests/limits when backend doesn't provide it.
  // The k8s-resources collector sends containers[].cpuRequest/cpuLimit/memoryRequest/memoryLimit
  // but the backend does not aggregate them into podResourceMetrics — so we do it here.
  const derivedPodResourceMetrics = useMemo(() => {
    // Prefer backend-computed value when available
    if (dashboard?.podResourceMetrics) return { ...dashboard.podResourceMetrics, _derived: false };

    const pods: any[] = dashboard?.k8sPods?.length
      ? dashboard.k8sPods
      : (dashboard?.workloadHealth?.pods || []);

    if (pods.length === 0) return null;

    let totalCpuRequests = 0;
    let totalCpuLimits = 0;
    let totalMemoryRequests = 0;
    let totalMemoryLimits = 0;
    let podsWithoutRequests = 0;
    let podsWithoutLimits = 0;

    pods.forEach((pod: any) => {
      const containers: any[] = pod.containers || pod.containerStatuses || [];
      let podHasRequests = false;
      let podHasLimits = false;

      containers.forEach((c: any) => {
        const cpuReq = parseCpuMillicores(c.cpuRequest);
        const cpuLim = parseCpuMillicores(c.cpuLimit);
        const memReq = parseMemoryMiB(c.memoryRequest);
        const memLim = parseMemoryMiB(c.memoryLimit);

        totalCpuRequests += cpuReq;
        totalCpuLimits += cpuLim;
        totalMemoryRequests += memReq;
        totalMemoryLimits += memLim;

        if (cpuReq > 0 || memReq > 0) podHasRequests = true;
        if (cpuLim > 0 || memLim > 0) podHasLimits = true;
      });

      if (!podHasRequests) podsWithoutRequests++;
      if (!podHasLimits) podsWithoutLimits++;
    });

    // Node allocatable capacity (sum across all nodes)
    const nodes: any[] = dashboard?.nodeMetrics?.nodes?.length
      ? dashboard.nodeMetrics.nodes
      : (dashboard?.resourceUtilization?.nodes || []);

    let totalCpuCapacity = 0;
    let totalMemoryCapacity = 0;
    nodes.forEach((n: any) => {
      totalCpuCapacity += parseCpuMillicores(n.allocatableCpu ?? n.cpuAllocatable ?? n.cpuCapacity ?? n.totalCpu);
      totalMemoryCapacity += parseMemoryMiB(n.allocatableMemory ?? n.memoryAllocatable ?? n.memoryCapacity ?? n.totalMemory);
    });

    // When node capacity is unavailable, estimate from requests (so chart still renders)
    if (totalCpuCapacity === 0 && totalCpuRequests > 0) totalCpuCapacity = Math.round(totalCpuRequests * 1.5);
    if (totalMemoryCapacity === 0 && totalMemoryRequests > 0) totalMemoryCapacity = Math.round(totalMemoryRequests * 1.5);

    if (totalCpuRequests === 0 && totalMemoryRequests === 0) return null;

    return {
      totalCpuRequests,
      totalCpuLimits,
      totalCpuCapacity,
      totalCpuUsage: 0,       // Prometheus not available — usage unknown
      totalMemoryRequests,
      totalMemoryLimits,
      totalMemoryCapacity,
      totalMemoryUsage: 0,    // Prometheus not available — usage unknown
      podsWithoutRequests,
      podsWithoutLimits,
      _derived: true,
    };
  }, [dashboard]);

  // Helper functions
  const getPodsCountByPhase = useCallback((phase: string) => {
    // prefer k8sPods (from k8s-resources collector) over workloadHealth.pods
    const pods = (dashboard?.k8sPods?.length ? dashboard.k8sPods : dashboard?.workloadHealth?.pods) || [];
    return pods.filter((p: any) => p.phase === phase).length;
  }, [dashboard]);

  const getPodsCountByQoS = useCallback((qosClass: string) => {
    const pods = dashboard?.workloadHealth?.pods || [];
    return pods.filter(p => p.qosClass === qosClass).length;
  }, [dashboard]);

  const getNodeConditionCount = useCallback((condition: string) => {
    const nodeMetricNodes = dashboard?.nodeMetrics?.nodes || [];
    const resUtilNodes = dashboard?.resourceUtilization?.nodes || [];

    // Helper: resolve ready from any node object regardless of shape
    const isNodeReady = (n: any) =>
      n.ready === true ||
      n.condition === 'Ready' || n.condition === 'ready' ||
      n.conditions?.some((c: any) => c.type === 'Ready' && c.status === 'True');

    const hasConditionPressure = (n: any, type: string) =>
      n.conditions?.some((c: any) => c.type === type && c.status === 'True') || false;

    if (condition === 'ready') {
      if (nodeMetricNodes.length > 0) {
        const unique = nodeMetricNodes.reduce((acc: any[], node: any) => {
          const key = node.name || node.nodeName;
          if (key && !acc.find((n: any) => (n.name || n.nodeName) === key)) acc.push(node);
          return acc;
        }, []);
        const count = unique.filter(isNodeReady).length;
        if (count > 0) return count;
      }
      // Fallback: resourceUtilization condition string
      const fromResUtil = resUtilNodes.filter((n: any) =>
        n.condition === 'Ready' || n.condition === 'ready'
      ).length;
      if (fromResUtil > 0) return fromResUtil;
      // Last resort: clusterOverview.totalNodes (all nodes are typically Ready in healthy clusters)
      return dashboard?.clusterOverview?.totalNodes || 0;
    }

    const uniqueNodes = nodeMetricNodes.reduce((acc: any[], node: any) => {
      const key = node.name || node.nodeName;
      if (key && !acc.find((n: any) => (n.name || n.nodeName) === key)) acc.push(node);
      return acc;
    }, []);

    switch (condition) {
      case 'diskPressure':   return uniqueNodes.filter((n: any) => n.diskPressure   || hasConditionPressure(n, 'DiskPressure')).length;
      case 'memoryPressure': return uniqueNodes.filter((n: any) => n.memoryPressure || hasConditionPressure(n, 'MemoryPressure')).length;
      case 'pidPressure':    return uniqueNodes.filter((n: any) => n.pidPressure    || hasConditionPressure(n, 'PIDPressure')).length;
      default: return 0;
    }
  }, [dashboard]);

  const getContainersCountByState = useCallback((state: string) => {
    const pods = dashboard?.workloadHealth?.pods || [];
    let count = 0;

    pods.forEach(pod => {
      const podContainers = pod.containers || pod.containerStatuses;
      if (!podContainers) return;
      podContainers.forEach(container => {
        if (container.state === state) count++;
      });
    });

    return count;
  }, [dashboard]);

  const getTimeSince = useCallback((timestamp: number) => {
    if (!timestamp) return 'Never';
    const seconds = Math.floor((Date.now() - timestamp) / 1000);
    if (seconds < 5) return 'Just now';
    if (seconds < 60) return `${seconds}s ago`;
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours}h ago`;
    return `${Math.floor(hours / 24)}d ago`;
  }, []);

  // Initial fetch
  useEffect(() => {
    if (clusterName && accountId) {
      fetchDashboard(false);
    }
  }, [clusterName, accountId, fetchDashboard]);

  return {
    dashboard,
    loading,
    isRefreshing,
    isSyncing,
    syncTimedOut,
    error,
    lastUpdateTimestamp,
    containerSearchQuery,
    setContainerSearchQuery,
    containerNamespaceFilter,
    setContainerNamespaceFilter,
    fetchDashboard,
    refresh,
    syncInBackground,
    clusterHealth,
    cpuColor,
    memoryColor,
    costEfficiency,
    idleCostPercentage,
    totalPodsCount,
    sortedNodesByCost,
    sortedNodesByPressure,
    clusterHealthScore,
    resourceScore,
    costScore,
    securityScore,
    healthScoreLabel,
    healthScoreColor,
    potentialSavings,
    quickWins,
    securityStatus,
    sortedAlertsByPriority,
    karpenterOverallStatus,
    karpenterProvisioningStatus,
    totalContainersCount,
    totalContainerRestarts,
    allContainersWithMetrics,
    filteredContainers,
    uniqueNamespaces,
    normalizedPods,
    derivedNodeRequestMetrics,
    derivedPodResourceMetrics,
    getPodsCountByPhase,
    getPodsCountByQoS,
    getNodeConditionCount,
    getContainersCountByState,
    getTimeSince
  };
};

export default useEksDashboard;
