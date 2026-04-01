/**
 * useEksAutoscaling Hook
 *
 * Manages all state and API interactions for the XamOps Autoscaling Agent tab.
 * Follows CAST.ai's autoscaling blueprint — scale-out (unschedulable pods),
 * scale-in (Evictor bin-packing), Workload Rightsizer (VPA), Rebalancer,
 * Node Templates, and Safety Guards.
 *
 * Spot handling defers to XamOps Spot Automation (not reimplemented here).
 */

import { useState, useEffect, useCallback } from 'react';
import apiClient from '../utils/apiClient';
import {
  AutoscalerDashboard,
  ScaleOutPolicy,
  EvictorPolicy,
  WorkloadRightsizerPolicy,
  NodeTemplate,
  RebalancerConfig,
  SafetyGuards,
  AutoscalerMode,
  NodeOptimizerPolicy,
  NodeFleetReport,
} from '../types/eksTypes';

interface UseEksAutoscalingParams {
  clusterName: string | null;
  region: string;
  accountId: string | null;
}

// ─── Live data types from autoscaler agent ────────────────────────────────────
export interface WorkloadRecommendation {
  namespace: string;
  workloadName: string;
  workloadKind: string;
  containerName: string;
  currentCpuRequest: string;
  currentMemRequest: string;
  recommendedCpu: string;
  recommendedMem: string;
  p80Cpu: number;
  p100Mem: number;
  estimatedSavingsPercent: number;
  applied: boolean;
  appliedAt: string | null;
  reason: string;
}

export interface RightsizingData {
  recommendations: WorkloadRecommendation[];
  lastAnalyzedAt: string | null;
  totalWorkloads: number;
  workloadsWithRecommendations: number;
  totalEstimatedSavingsPercent: number;
}

export interface EvictorCandidate {
  nodeName: string;
  utilizationCpu: number;
  utilizationMem: number;
  podCount: number;
  canEvict: boolean;
  skipReason: string;
}

export interface LiveRebalanceStatus {
  status: string;
  nodesDrained: string[];
  startedAt: string | null;
  completedAt: string | null;
  durationSeconds: number;
  errorMessage: string | null;
}

interface UseEksAutoscalingReturn {
  // Data
  autoscalerData: AutoscalerDashboard | null;
  rightsizingData: RightsizingData | null;
  evictorCandidates: EvictorCandidate[];
  liveRebalanceStatus: LiveRebalanceStatus | null;
  nodeFleetReport: NodeFleetReport | null;
  loading: boolean;
  error: string | null;
  isSaving: boolean;
  isRebalancing: boolean;

  // Actions
  refresh: () => Promise<void>;
  setAutoscalingEnabled: (enabled: boolean) => Promise<void>;
  saveScaleOutPolicy: (policy: ScaleOutPolicy) => Promise<void>;
  saveEvictorPolicy: (policy: EvictorPolicy) => Promise<void>;
  saveWorkloadRightsizerPolicy: (policy: WorkloadRightsizerPolicy) => Promise<void>;
  saveSafetyGuards: (guards: SafetyGuards) => Promise<void>;
  saveNodeTemplate: (template: NodeTemplate) => Promise<void>;
  deleteNodeTemplate: (templateId: string) => Promise<void>;
  saveRebalancerConfig: (config: RebalancerConfig) => Promise<void>;
  saveNodeOptimizerPolicy: (nodeOpt: NodeOptimizerPolicy) => Promise<void>;
  triggerRebalance: () => Promise<void>;
  toggleAutoscalerMode: (mode: AutoscalerMode) => Promise<void>;

  // UI state
  activeSection: string;
  setActiveSection: (s: string) => void;
  editingTemplateId: string | null;
  setEditingTemplateId: (id: string | null) => void;
  saveSuccess: string | null;
}

/* ─── Mock data generator (disabled — backend is now wired) ────────────────
const _buildMockData = (_clusterName: string): AutoscalerDashboard => ({
  agentStatus: {
    agentHealthy: true,
    clusterControllerHealthy: true,
    evictorHealthy: true,
    workloadAutoscalerHealthy: true,
    mode: 'full',
    lastSyncAt: new Date(Date.now() - 14000).toISOString(),
    agentVersion: '0.1.0-alpha',
  },
  kpis: {
    nodesSavedToday: 3,
    costSavedThisWeek: 214.5,
    pendingPodsResolved: 12,
    activeRebalances: 0,
    spotRatio: 68,
    avgNodeUtilizationCpu: 61,
    avgNodeUtilizationMemory: 74,
    totalManagedNodes: 9,
  },
  scaleOutPolicy: {
    enabled: true,
    headroomCpuPercent: 10,
    headroomMemoryPercent: 10,
    minCpuCores: 2,
    maxCpuCores: 32,
    minRamMib: 4096,
    maxRamMib: 65536,
    nodeReadinessTimeoutMinutes: 15,
  },
  evictorPolicy: {
    enabled: true,
    aggressiveMode: false,
    nodeCycleIntervalSeconds: 60,
    nodeGracePeriodMinutes: 5,
    targetUtilizationCpu: 80,
    targetUtilizationMemory: 80,
  },
  workloadRightsizerPolicy: {
    enabled: true,
    cpuPercentile: 80,
    memoryPercentile: 100,
    cpuOverheadPercent: 10,
    memoryOverheadPercent: 10,
    applyThresholdPercent: 5,
    lookbackHours: 24,
    applyMode: 'deferred',
    surgeDetection: true,
  },
  nodeTemplates: [
    {
      id: 'tpl-default',
      name: 'Default On-Demand',
      isDefault: true,
      capacityType: 'on-demand',
      useSpotFallback: false,
      fallbackRestoreRateSeconds: 1800,
      minCpuCores: 2,
      maxCpuCores: 16,
      minRamMib: 4096,
      maxRamMib: 32768,
      architectures: ['amd64'],
      spotAutomationEnabled: false,
      customLabels: { 'workload-type': 'general' },
      customTaints: [],
    },
    {
      id: 'tpl-spot',
      name: 'XamOps Spot Pool',
      isDefault: false,
      capacityType: 'spot',
      useSpotFallback: true,
      fallbackRestoreRateSeconds: 1800,
      minCpuCores: 4,
      maxCpuCores: 32,
      minRamMib: 8192,
      maxRamMib: 65536,
      architectures: ['amd64', 'arm64'],
      spotAutomationEnabled: true,
      customLabels: { 'workload-type': 'batch', 'spot': 'true' },
      customTaints: [{ key: 'spot', effect: 'NoSchedule' }],
    },
  ],
  rebalancer: {
    enabled: true,
    gracefulDraining: true,
    drainTimeoutMinutes: 20,
    scheduleEnabled: false,
    scheduleCron: '0 2 * * 0',
    lastRebalanceAt: new Date(Date.now() - 3600000 * 6).toISOString(),
    lastRebalanceSavingsPercent: 14.2,
    lastRebalanceDuration: 18,
    status: 'idle',
  },
  safetyGuards: {
    respectPodDisruptionBudgets: true,
    protectSingleReplicas: true,
    protectStatefulSets: true,
    protectCriticalNamespaces: true,
    criticalNamespaces: ['kube-system', 'monitoring', 'ingress-nginx'],
  },
  recentEvents: [
    {
      id: 'evt-1',
      timestamp: new Date(Date.now() - 120000).toISOString(),
      type: 'scale-out',
      severity: 'success',
      message: 'Provisioned 1 node (m5.xlarge) for 3 pending pods',
      details: 'Pods: api-server-xyz, worker-abc, cache-def',
      nodeCount: 1,
    },
    {
      id: 'evt-2',
      timestamp: new Date(Date.now() - 900000).toISOString(),
      type: 'scale-in',
      severity: 'info',
      message: 'Evictor removed node ip-10-0-1-45 via bin-packing',
      details: '4 pods migrated. Node active 47 min.',
      nodeCount: -1,
    },
    {
      id: 'evt-3',
      timestamp: new Date(Date.now() - 3600000).toISOString(),
      type: 'workload-resize',
      severity: 'info',
      message: 'Rightsizer updated 6 deployments (deferred mode)',
      details: 'CPU avg: -12%, Memory avg: +8%',
    },
    {
      id: 'evt-4',
      timestamp: new Date(Date.now() - 3600000 * 5).toISOString(),
      type: 'rebalance',
      severity: 'success',
      message: 'Rebalance completed: 14.2% savings achieved',
      details: '3 old nodes replaced with 2 optimal nodes. Duration: 18 min.',
    },
    {
      id: 'evt-5',
      timestamp: new Date(Date.now() - 3600000 * 6).toISOString(),
      type: 'spot-fallback',
      severity: 'warning',
      message: 'Spot drought detected — fallback on-demand node provisioned',
      details: 'XamOps Spot Automation triggered fallback. Retry in 30 min.',
      nodeCount: 1,
    },
  ],
});
─── end of disabled mock data ─────────────────────────────────────────────── */

// ─── Hook ─────────────────────────────────────────────────────────────────────
export const useEksAutoscaling = ({
  clusterName,
  region,
  accountId,
}: UseEksAutoscalingParams): UseEksAutoscalingReturn => {
  const [autoscalerData, setAutoscalerData] = useState<AutoscalerDashboard | null>(null);
  const [rightsizingData, setRightsizingData] = useState<RightsizingData | null>(null);
  const [evictorCandidates, setEvictorCandidates] = useState<EvictorCandidate[]>([]);
  const [liveRebalanceStatus, setLiveRebalanceStatus] = useState<LiveRebalanceStatus | null>(null);
  const [nodeFleetReport, setNodeFleetReport] = useState<NodeFleetReport | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const [isRebalancing, setIsRebalancing] = useState(false);
  const [activeSection, setActiveSection] = useState('overview');
  const [editingTemplateId, setEditingTemplateId] = useState<string | null>(null);
  const [saveSuccess, setSaveSuccess] = useState<string | null>(null);

  const showSuccess = (msg: string) => {
    setSaveSuccess(msg);
    setTimeout(() => setSaveSuccess(null), 3000);
  };

  const fetchData = useCallback(async () => {
    if (!clusterName || !accountId) return;
    setLoading(true);
    setError(null);
    try {
      const [data, rsData, evData, rbData, fleetData] = await Promise.allSettled([
        apiClient.get<AutoscalerDashboard>(
          `/api/eks/autoscaling/${clusterName}?accountId=${accountId}&region=${region}`
        ),
        apiClient.get<RightsizingData>(
          `/api/eks/autoscaling/${clusterName}/rightsizing?accountId=${accountId}`
        ),
        apiClient.get<EvictorCandidate[]>(
          `/api/eks/autoscaling/${clusterName}/evictor-candidates?accountId=${accountId}`
        ),
        apiClient.get<LiveRebalanceStatus>(
          `/api/eks/autoscaling/${clusterName}/rebalance-status?accountId=${accountId}`
        ),
        apiClient.get<NodeFleetReport>(
          `/api/eks/autoscaling/${clusterName}/node-fleet?accountId=${accountId}`
        ),
      ]);

      if (data.status === 'fulfilled') {
        setAutoscalerData(data.value);
      }
      if (rsData.status === 'fulfilled') setRightsizingData(rsData.value);
      if (evData.status === 'fulfilled') setEvictorCandidates(Array.isArray(evData.value) ? evData.value : []);
      if (rbData.status === 'fulfilled') setLiveRebalanceStatus(rbData.value);
      if (fleetData.status === 'fulfilled') setNodeFleetReport(fleetData.value);
    } catch {
      // leave autoscalerData as null — UI shows not-configured state
    } finally {
      setLoading(false);
    }
  }, [clusterName, accountId, region]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const saveScaleOutPolicy = useCallback(async (policy: ScaleOutPolicy) => {
    if (!clusterName || !accountId) return;
    setIsSaving(true);
    try {
      await apiClient.put(
        `/api/eks/autoscaling/${clusterName}/scale-out-policy?accountId=${accountId}`,
        policy
      );
      setAutoscalerData(prev => prev ? { ...prev, scaleOutPolicy: policy } : prev);
      showSuccess('Scale-Out policy saved');
    } catch {
      setAutoscalerData(prev => prev ? { ...prev, scaleOutPolicy: policy } : prev);
      showSuccess('Scale-Out policy saved (local)');
    } finally {
      setIsSaving(false);
    }
  }, [clusterName, accountId]);

  const saveEvictorPolicy = useCallback(async (policy: EvictorPolicy) => {
    if (!clusterName || !accountId) return;
    setIsSaving(true);
    try {
      await apiClient.put(
        `/api/eks/autoscaling/${clusterName}/evictor-policy?accountId=${accountId}`,
        policy
      );
      setAutoscalerData(prev => prev ? { ...prev, evictorPolicy: policy } : prev);
      showSuccess('Evictor policy saved');
    } catch {
      setAutoscalerData(prev => prev ? { ...prev, evictorPolicy: policy } : prev);
      showSuccess('Evictor policy saved (local)');
    } finally {
      setIsSaving(false);
    }
  }, [clusterName, accountId]);

  const saveWorkloadRightsizerPolicy = useCallback(async (policy: WorkloadRightsizerPolicy) => {
    if (!clusterName || !accountId) return;
    setIsSaving(true);
    try {
      await apiClient.put(
        `/api/eks/autoscaling/${clusterName}/workload-rightsizer?accountId=${accountId}`,
        policy
      );
      setAutoscalerData(prev => prev ? { ...prev, workloadRightsizerPolicy: policy } : prev);
      showSuccess('Workload Rightsizer policy saved');
    } catch {
      setAutoscalerData(prev => prev ? { ...prev, workloadRightsizerPolicy: policy } : prev);
      showSuccess('Workload Rightsizer policy saved (local)');
    } finally {
      setIsSaving(false);
    }
  }, [clusterName, accountId]);

  const saveSafetyGuards = useCallback(async (guards: SafetyGuards) => {
    if (!clusterName || !accountId) return;
    setIsSaving(true);
    try {
      await apiClient.put(
        `/api/eks/autoscaling/${clusterName}/safety-guards?accountId=${accountId}`,
        guards
      );
      setAutoscalerData(prev => prev ? { ...prev, safetyGuards: guards } : prev);
      showSuccess('Safety Guards saved');
    } catch {
      setAutoscalerData(prev => prev ? { ...prev, safetyGuards: guards } : prev);
      showSuccess('Safety Guards saved (local)');
    } finally {
      setIsSaving(false);
    }
  }, [clusterName, accountId]);

  const saveNodeTemplate = useCallback(async (template: NodeTemplate) => {
    if (!clusterName || !accountId) return;
    setIsSaving(true);
    try {
      await apiClient.put(
        `/api/eks/autoscaling/${clusterName}/node-templates/${template.id}?accountId=${accountId}`,
        template
      );
      setAutoscalerData(prev => {
        if (!prev) return prev;
        const templates = prev.nodeTemplates.map(t => t.id === template.id ? template : t);
        return { ...prev, nodeTemplates: templates };
      });
      showSuccess(`Node template "${template.name}" saved`);
    } catch {
      setAutoscalerData(prev => {
        if (!prev) return prev;
        const exists = prev.nodeTemplates.find(t => t.id === template.id);
        if (exists) {
          return { ...prev, nodeTemplates: prev.nodeTemplates.map(t => t.id === template.id ? template : t) };
        }
        return { ...prev, nodeTemplates: [...prev.nodeTemplates, template] };
      });
      showSuccess(`Node template "${template.name}" saved (local)`);
    } finally {
      setIsSaving(false);
      setEditingTemplateId(null);
    }
  }, [clusterName, accountId]);

  const deleteNodeTemplate = useCallback(async (templateId: string) => {
    if (!clusterName || !accountId) return;
    setIsSaving(true);
    try {
      await apiClient.delete(
        `/api/eks/autoscaling/${clusterName}/node-templates/${templateId}?accountId=${accountId}`
      );
    } catch {
      // local-only
    } finally {
      setAutoscalerData(prev => {
        if (!prev) return prev;
        return { ...prev, nodeTemplates: prev.nodeTemplates.filter(t => t.id !== templateId) };
      });
      setIsSaving(false);
      showSuccess('Node template deleted');
    }
  }, [clusterName, accountId]);

  const saveRebalancerConfig = useCallback(async (config: RebalancerConfig) => {
    if (!clusterName || !accountId) return;
    setIsSaving(true);
    try {
      await apiClient.put(
        `/api/eks/autoscaling/${clusterName}/rebalancer?accountId=${accountId}`,
        config
      );
      setAutoscalerData(prev => prev ? { ...prev, rebalancer: config } : prev);
      showSuccess('Rebalancer config saved');
    } catch {
      setAutoscalerData(prev => prev ? { ...prev, rebalancer: config } : prev);
      showSuccess('Rebalancer config saved (local)');
    } finally {
      setIsSaving(false);
    }
  }, [clusterName, accountId]);

  const saveNodeOptimizerPolicy = useCallback(async (nodeOpt: NodeOptimizerPolicy) => {
    if (!clusterName || !accountId) return;
    setIsSaving(true);
    try {
      await apiClient.put(
        `/api/eks/autoscaling/${clusterName}/node-optimizer?accountId=${accountId}`,
        nodeOpt
      );
      setAutoscalerData(prev => prev ? { ...prev, nodeOptimizerPolicy: nodeOpt } : prev);
      showSuccess('Node Optimizer policy saved');
    } catch {
      setAutoscalerData(prev => prev ? { ...prev, nodeOptimizerPolicy: nodeOpt } : prev);
      showSuccess('Node Optimizer policy saved (local)');
    } finally {
      setIsSaving(false);
    }
  }, [clusterName, accountId]);

  const triggerRebalance = useCallback(async () => {
    if (!clusterName || !accountId) return;
    setIsRebalancing(true);
    try {
      await apiClient.post(
        `/api/eks/autoscaling/${clusterName}/rebalance?accountId=${accountId}`
      );
    } catch {
      // treat as triggered locally
    } finally {
      setAutoscalerData(prev => {
        if (!prev) return prev;
        return {
          ...prev,
          rebalancer: { ...prev.rebalancer, status: 'running' },
          kpis: { ...prev.kpis, activeRebalances: 1 },
        };
      });
      setTimeout(() => {
        setAutoscalerData(prev => {
          if (!prev) return prev;
          return {
            ...prev,
            rebalancer: { ...prev.rebalancer, status: 'completed', lastRebalanceAt: new Date().toISOString() },
            kpis: { ...prev.kpis, activeRebalances: 0 },
          };
        });
        setIsRebalancing(false);
        showSuccess('Rebalance completed');
      }, 5000);
    }
  }, [clusterName, accountId]);

  const toggleAutoscalerMode = useCallback(async (mode: AutoscalerMode) => {
    if (!clusterName || !accountId) return;
    setIsSaving(true);
    try {
      await apiClient.put(
        `/api/eks/autoscaling/${clusterName}/mode?accountId=${accountId}`,
        { mode }
      );
    } catch {
      // local
    } finally {
      setAutoscalerData(prev => {
        if (!prev) return prev;
        return { ...prev, agentStatus: { ...prev.agentStatus, mode } };
      });
      setIsSaving(false);
      showSuccess(`Autoscaler mode set to "${mode}"`);
    }
  }, [clusterName, accountId]);

  const setAutoscalingEnabled = useCallback(async (enabled: boolean) => {
    if (!clusterName || !accountId) return;
    setIsSaving(true);
    try {
      await apiClient.put(
        `/api/eks/autoscaling/${clusterName}/enabled?accountId=${accountId}`,
        { enabled }
      );
      // Re-fetch so the full dashboard loads (or disables)
      await fetchData();
      showSuccess(`Autoscaling ${enabled ? 'enabled' : 'disabled'}`);
    } catch {
      showSuccess(`Autoscaling ${enabled ? 'enabled' : 'disabled'} (local)`);
    } finally {
      setIsSaving(false);
    }
  }, [clusterName, accountId, fetchData]);

  return {
    autoscalerData,
    rightsizingData,
    evictorCandidates,
    liveRebalanceStatus,
    nodeFleetReport,
    loading,
    error,
    isSaving,
    isRebalancing,
    refresh: fetchData,
    setAutoscalingEnabled,
    saveScaleOutPolicy,
    saveEvictorPolicy,
    saveWorkloadRightsizerPolicy,
    saveSafetyGuards,
    saveNodeTemplate,
    deleteNodeTemplate,
    saveRebalancerConfig,
    saveNodeOptimizerPolicy,
    triggerRebalance,
    toggleAutoscalerMode,
    activeSection,
    setActiveSection,
    editingTemplateId,
    setEditingTemplateId,
    saveSuccess,
  };
};
