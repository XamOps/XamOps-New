import { render, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AutoscalerDashboard, NodeFleetReport } from '../../../../types/eksTypes';

// The tab pulls all of its data from useEksAutoscaling; mock the hook so the render is deterministic
// and we can drive the agentStatus / actuatorState split (D2).
const hookReturn = vi.hoisted(() => ({ value: null as unknown }));
vi.mock('../../../../hooks/useEksAutoscaling', () => ({
  useEksAutoscaling: () => hookReturn.value,
}));

import EksAutoscalingTab from '../EksAutoscalingTab';

/** A complete, actuator-disabled dashboard with a live agent — the evidence/08-node.json scenario. */
function analysisOnlyDashboard(): AutoscalerDashboard {
  return {
    autoscalingEnabled: true,
    agentStatus: {
      agentHealthy: true,
      clusterControllerHealthy: false,
      evictorHealthy: false,
      workloadAutoscalerHealthy: false,
      mode: 'disabled',
      actuatorState: 'DISABLED',
      lastSyncAt: new Date().toISOString(),
      agentVersion: '0.1.0',
    },
    kpis: {
      nodesSavedToday: 0,
      costSavedThisWeek: 0,
      pendingPodsResolved: 0,
      activeRebalances: 0,
      spotRatio: 0,
      avgNodeUtilizationCpu: 0,
      avgNodeUtilizationMemory: 0,
      totalManagedNodes: 0,
    },
    scaleOutPolicy: {
      enabled: true, headroomCpuPercent: 10, headroomMemoryPercent: 10,
      minCpuCores: 2, maxCpuCores: 32, minRamMib: 4096, maxRamMib: 65536,
      nodeReadinessTimeoutMinutes: 15,
    },
    evictorPolicy: {
      enabled: true, analyseOnly: true, aggressiveMode: false, nodeCycleIntervalSeconds: 60,
      nodeGracePeriodMinutes: 5, targetUtilizationCpu: 80, targetUtilizationMemory: 80,
    },
    workloadRightsizerPolicy: {
      enabled: true, cpuPercentile: 80, memoryPercentile: 100, cpuOverheadPercent: 10,
      memoryOverheadPercent: 10, applyThresholdPercent: 5, lookbackHours: 72,
      applyMode: 'deferred', surgeDetection: true,
    },
    nodeTemplates: [],
    rebalancer: {
      enabled: true, gracefulDraining: true, drainTimeoutMinutes: 20, scheduleEnabled: false,
      scheduleCron: '0 2 * * 0', lastRebalanceAt: null, lastRebalanceSavingsPercent: 0,
      lastRebalanceDuration: 0, status: 'idle',
    },
    safetyGuards: {
      respectPodDisruptionBudgets: true, protectSingleReplicas: true, protectStatefulSets: true,
      protectCriticalNamespaces: true, criticalNamespaces: ['kube-system'],
    },
    nodeOptimizerPolicy: {
      enabled: false, idleCpuThresholdPct: 10, idleMemThresholdPct: 20, autoCordonIdleNodes: false,
      autoDrainIdleNodes: false, handleSpotInterruptions: false, spotRatioTargetPct: 0,
      karpenterEnabled: false, maxParallelDrains: 1,
    },
    recentEvents: [],
  } as unknown as AutoscalerDashboard;
}

function mockHook(dashboard: AutoscalerDashboard, nodeFleetReport: NodeFleetReport | null = null) {
  hookReturn.value = {
    autoscalerData: dashboard,
    rightsizingData: null,
    evictorCandidates: [],
    liveRebalanceStatus: null,
    nodeFleetReport,
    loading: false,
    error: null,
    isSaving: false,
    isRebalancing: false,
    refresh: vi.fn(),
    setAutoscalingEnabled: vi.fn(),
    saveScaleOutPolicy: vi.fn(),
    saveEvictorPolicy: vi.fn(),
    saveWorkloadRightsizerPolicy: vi.fn(),
    saveSafetyGuards: vi.fn(),
    saveNodeTemplate: vi.fn(),
    deleteNodeTemplate: vi.fn(),
    saveRebalancerConfig: vi.fn(),
    saveNodeOptimizerPolicy: vi.fn(),
    triggerRebalance: vi.fn(),
    toggleAutoscalerMode: vi.fn(),
    activeSection: 'overview',
    setActiveSection: vi.fn(),
    editingTemplateId: null,
    setEditingTemplateId: vi.fn(),
    saveSuccess: null,
  };
}

describe('EksAutoscalingTab — agent vs actuator health split (D2)', () => {
  beforeEach(() => {
    window.history.pushState({}, '', '/?name=prod-eks&region=us-east-1&accountIds=1');
  });
  afterEach(() => {
    hookReturn.value = null;
  });

  it('shows the Agent row healthy and actuator component rows "Analysis-only" when actuatorState=DISABLED', () => {
    mockHook(analysisOnlyDashboard());
    const { getByText } = render(<EksAutoscalingTab eksData={undefined} />);

    // The agent is live: its row reads Healthy, not Offline. Scope to the component status grid,
    // since some labels (e.g. "Workload Rightsizer") also appear as section titles elsewhere.
    const agentCard = getByText('Agent').closest('.rounded-md') as HTMLElement;
    const statusGrid = within(agentCard.parentElement as HTMLElement);
    expect(within(agentCard).getByText('Healthy')).toBeTruthy();
    expect(within(agentCard).queryByText('Analysis-only')).toBeNull();

    // Actuator components read "Analysis-only" (neutral), never "Offline", when the actuator is disabled.
    for (const label of ['Cluster Controller', 'Evictor', 'Workload Rightsizer']) {
      const card = statusGrid.getByText(label).closest('.rounded-md') as HTMLElement;
      expect(within(card).getByText('Analysis-only')).toBeTruthy();
      expect(within(card).queryByText('Offline')).toBeNull();
    }

    // A live agent on an analysis-only actuator is not "Degraded".
    expect(getByText('All Systems Healthy')).toBeTruthy();
  });

  it('labels the actuation KPI tile "Actuated nodes"', () => {
    mockHook(analysisOnlyDashboard());
    const { getByText, queryByText } = render(<EksAutoscalingTab eksData={undefined} />);

    expect(getByText('Actuated nodes')).toBeTruthy();
    expect(queryByText('Managed Nodes')).toBeNull();
  });
});

describe('EksAutoscalingTab — nullable node-fleet lists (spot-interruptions design §B5.1)', () => {
  beforeEach(() => {
    window.history.pushState({}, '', '/?name=prod-eks&region=us-east-1&accountIds=1');
  });
  afterEach(() => {
    hookReturn.value = null;
  });

  const fleet = (over: Partial<NodeFleetReport>): NodeFleetReport => ({
    totalNodes: 3, onDemandNodes: 2, spotNodes: 1, idleNodes: [], spotCandidates: [], interruptedNodes: [],
    estimatedSavingsPct: null, collectedAt: new Date().toISOString(), ...over,
  });

  it('renders a spot candidate with estimatedSavingsPct null, without the savings text', () => {
    const candidate = { nodeName: 'ip-10-0-1-5', instanceType: 't3.medium', cpuUsagePct: 10, memUsagePct: 20, savingsPct: 60 } as unknown as NonNullable<NodeFleetReport['spotCandidates']>[number];
    mockHook(analysisOnlyDashboard(), fleet({ spotCandidates: [candidate], estimatedSavingsPct: null }));
    const { getByText, queryByText } = render(<EksAutoscalingTab eksData={undefined} />);
    expect(getByText(/Spot Conversion Candidates \(1 on-demand nodes\)/)).toBeTruthy();
    expect(queryByText(/savings potential/)).toBeNull();
  });

  it('hides the idle and spot-candidate sections when the lists are null (not observed)', () => {
    mockHook(analysisOnlyDashboard(), fleet({ idleNodes: null, spotCandidates: null, interruptedNodes: null }));
    const { queryByText } = render(<EksAutoscalingTab eksData={undefined} />);
    expect(queryByText(/Idle Nodes \(/)).toBeNull();
    expect(queryByText(/Spot Conversion Candidates/)).toBeNull();
  });
});
