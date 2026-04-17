/**
 * useEksDashboard Hook
 *
 * Custom hook for EKS cluster dashboard data fetching and state management
 * Converted from: eks-details.html Alpine.js logic
 */

import { useState, useCallback, useEffect, useMemo } from 'react';
import apiClient from '../utils/apiClient';
import {
  EksDashboard,
  ContainerWithMetrics,
  PotentialSavings,
  QuickWin,
  KarpenterStatus,
  TabType,
  NodeCost
} from '../types/eksTypes';
import { useEksDashboardCache } from '../contexts/EksDashboardCacheContext';

interface UseEksDashboardParams {
  clusterName: string | null;
  region: string;
  version: string;
  accountId: string | null;
}

interface UseEksDashboardReturn {
  dashboard: EksDashboard | null;
  loading: boolean;
  isRefreshing: boolean;
  isSyncing: boolean;
  error: string | null;
  lastUpdateTimestamp: number;
  activeTab: TabType;
  setActiveTab: (tab: TabType) => void;
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
  accountId
}: UseEksDashboardParams): UseEksDashboardReturn => {
  // Frontend cache context - persists data across navigation
  const dashboardCache = useEksDashboardCache();

  const [dashboard, setDashboard] = useState<EksDashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [isSyncing, setIsSyncing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastUpdateTimestamp, setLastUpdateTimestamp] = useState(Date.now());
  const [activeTab, setActiveTab] = useState<TabType>('overview');
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
        `/api/xamops/eks/${clusterName}/dashboard?accountId=${accountId}&region=${region}&version=${version}&forceRefresh=${forceRefresh}`
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
            `/api/xamops/eks/${clusterName}/dashboard?accountId=${accountId}&region=${region}&version=${version}&forceRefresh=false`
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
      while (attempts < maxAttempts) {
        await new Promise(resolve => setTimeout(resolve, 3000));
        attempts++;

        const updated = await pollForUpdate();
        if (updated) {
          console.log(`Sync completed after ${attempts * 3} seconds`);
          break;
        }
      }

      if (attempts >= maxAttempts) {
        console.warn('Sync polling timeout - data may still be syncing');
      }

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
    const nodes = dashboard?.resourceUtilization?.nodes || [];
    return [...nodes].sort((a, b) => {
      const pressureA = Math.max(a.cpuUsagePercent, a.memoryUsagePercent);
      const pressureB = Math.max(b.cpuUsagePercent, b.memoryUsagePercent);
      return pressureB - pressureA;
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
      return count + (pod.containerStatuses?.length || 0);
    }, 0);
  }, [dashboard]);

  const totalContainerRestarts = useMemo(() => {
    const pods = dashboard?.workloadHealth?.pods || [];
    return pods.reduce((total, pod) => {
      if (!pod.containerStatuses) return total;
      return total + pod.containerStatuses.reduce((sum, c) => sum + (c.restartCount || 0), 0);
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
      if (!pod.containerStatuses) return;

      pod.containerStatuses.forEach(containerStatus => {
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
    // Fallback: if nodeMetrics is not yet populated, use resourceUtilization.nodes (condition string)
    const resUtilNodes = dashboard?.resourceUtilization?.nodes || [];

    if (condition === 'ready') {
      // Try nodeMetrics boolean first (built by backend step 10)
      if (nodeMetricNodes.length > 0) {
        const unique = nodeMetricNodes.reduce((acc: any[], node) => {
          if (!acc.find((n: any) => n.name === node.name)) acc.push(node);
          return acc;
        }, []);
        return unique.filter((n: any) => n.ready).length;
      }
      // Fallback: count nodes with condition === 'Ready' from resourceUtilization
      return resUtilNodes.filter((n: any) =>
        n.condition === 'Ready' || n.condition === 'ready'
      ).length;
    }

    // For pressure conditions, only nodeMetrics has this data
    const uniqueNodes = nodeMetricNodes.reduce((acc: any[], node) => {
      if (!acc.find((n: any) => n.name === node.name)) acc.push(node);
      return acc;
    }, []);
    switch (condition) {
      case 'diskPressure': return uniqueNodes.filter((n: any) => n.diskPressure).length;
      case 'memoryPressure': return uniqueNodes.filter((n: any) => n.memoryPressure).length;
      case 'pidPressure': return uniqueNodes.filter((n: any) => n.pidPressure).length;
      default: return 0;
    }
  }, [dashboard]);

  const getContainersCountByState = useCallback((state: string) => {
    const pods = dashboard?.workloadHealth?.pods || [];
    let count = 0;

    pods.forEach(pod => {
      if (!pod.containerStatuses) return;
      pod.containerStatuses.forEach(container => {
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
    error,
    lastUpdateTimestamp,
    activeTab,
    setActiveTab,
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
    getPodsCountByPhase,
    getPodsCountByQoS,
    getNodeConditionCount,
    getContainersCountByState,
    getTimeSince
  };
};

export default useEksDashboard;
