/**
 * EKS Details Types
 *
 * TypeScript interfaces for EKS cluster dashboard data
 */

export interface EksDashboard {
  clusterOverview: ClusterOverview;
  costMetrics: CostMetrics;
  securityMetrics: SecurityMetrics;
  resourceUtilization: ResourceUtilization;
  workloadHealth: WorkloadHealth;
  nodeMetrics: NodeMetrics;
  containerMetrics: ContainerMetrics;
  kubescape: KubescapeData | null;
  trivy: TrivyData | null;
  karpenter: KarpenterData | null;
  podResourceMetrics?: PodResourceMetrics;
  // ── Agent-sourced fields (from k8s-resources + prometheus-metrics collectors) ──
  k8sNodes?: any[];
  k8sPods?: any[];
  k8sDeployments?: any[];
  k8sStatefulSets?: any[];
  k8sDaemonSets?: any[];
  k8sServices?: any[];
  k8sIngresses?: any[];
  k8sHpas?: any[];
  k8sPvcs?: any[];
  k8sJobs?: any[];
  k8sEvents?: any[];
  nodeUtilization?: any[];
  namespaceUtilization?: any[];
  falcoEvents?: any[];
  topCpuPods?: any[];
  topMemoryPods?: any[];
  networkMetrics?: any[];
  agentCostMetrics?: any;
  // Caching metadata
  lastSyncedAt?: string; // ISO timestamp from server
}

export interface ClusterOverview {
  totalNodes: number;
  totalNamespaces: number;
  cpuUsageMillicores: number;
  cpuCapacityMillicores: number;
  cpuUsagePercent: number;
  memoryUsageBytes: number;
  memoryCapacityBytes: number;
  memoryUsagePercent: number;
}

export interface CostMetrics {
  dailyCost: number;
  monthlyCost: number;
  nodeCosts: NodeCost[];
  namespaceCosts?: NamespaceCostAllocation[];
  workloadCosts?: WorkloadCostAllocation[];
}

export interface NodeCost {
  nodeName: string;
  instanceType: string;
  hourlyRate: number;
  dailyCost: number;
  cpuUsagePercent?: number;
}

export interface NamespaceCostAllocation {
  namespace: string;
  dailyCost: number;
  monthlyCost: number;
  cpuUsageMillicores: number;
  memoryUsageMb: number;
  podCount: number;
  costPercentage?: number;
}

export interface WorkloadCostAllocation {
  name: string;
  namespace: string;
  kind: string; // 'Deployment', 'DaemonSet', 'StatefulSet'
  dailyCost: number;
  monthlyCost: number;
  cpuUsageMillicores: number;
  memoryUsageMb: number;
  podCount: number;
  replicas?: number;
}

export interface SecurityMetrics {
  totalEvents: number;
  eventsByPriority: Record<string, number>;
  alerts: SecurityAlert[];
}

export interface SecurityAlert {
  rule: string;
  priority: string;
  count: number;
  lastSeen: string;
  namespace?: string;
}

export interface ResourceUtilization {
  nodes: NodeResource[];
}

export interface NodeResource {
  nodeName: string;
  cpuUsagePercent: number;
  memoryUsagePercent: number;
  cpuRequestsPercent: number;
  memoryRequestsPercent: number;
}

export interface WorkloadHealth {
  pods: PodInfo[];
  deployments: DeploymentInfo[];
}

export interface PodInfo {
  name: string;
  namespace: string;
  phase: string;
  qosClass: string;
  nodeName: string;
  containerStatuses?: ContainerStatus[];
  containers?: ContainerStatus[];
  cpuUsage?: number;
  memoryUsage?: number;
  podIp?: string;
  age?: string;
  ownerKind?: string;
  ownerName?: string;
  cpuRequest?: number;
  memoryRequest?: number;
  cpuLimit?: number;
  memoryLimit?: number;
}

export interface ContainerStatus {
  name: string;
  image: string;
  state: 'running' | 'waiting' | 'terminated';
  ready: boolean;
  restartCount: number;
  cpuRequest?: string;
  cpuLimit?: string;
  memoryRequest?: string;
  memoryLimit?: string;
  waitingReason?: string;
}

export interface DeploymentInfo {
  name: string;
  namespace: string;
  replicas: number;
  availableReplicas: number;
  readyReplicas: number;
}

export interface NodeMetrics {
  nodes: NodeInfo[];
}

export interface NodeInfo {
  name: string;
  ready: boolean;
  diskPressure: boolean;
  memoryPressure: boolean;
  pidPressure: boolean;
  instanceType: string;
  cpuCapacity: number;
  memoryCapacity: number;
  // NEW: Enhanced fields
  capacityType?: 'spot' | 'on-demand' | 'unknown';
  nodeGroup?: string;
  zone?: string;
  internalIp?: string;
  cpuAllocatable?: number;
  memoryAllocatable?: number;
  cpuUsage?: number;
  cpuUsageMillicores?: number;
  memoryUsage?: number;
  podCapacity?: number;
  podCount?: number;
  runningPodsCount?: number;
  systemReservedCpu?: number;
  systemReservedMemory?: number;
}

export interface ContainerMetrics {
  containers: ContainerMetric[];
}

export interface ContainerMetric {
  name: string;
  pod: string;
  namespace: string;
  cpuUsage: number;
  memoryUsage: number;
  fsUsage: number;
}

export interface KubescapeData {
  totalCritical: number;
  totalHigh: number;
  totalMedium: number;
  totalLow: number;
  totalWorkloads?: number;
  namespaceBreakdown: Record<string, NamespaceVulnerabilities>;
  frameworks: FrameworkScore[];
  vulnerabilities?: KubescapeVulnerableWorkload[];
}

export interface NamespaceVulnerabilities {
  critical: number;
  high: number;
  medium: number;
  low: number;
}

export interface FrameworkScore {
  name: string;
  score: number;
  passedControls: number;
  failedControls: number;
}

export interface KubescapeVulnerableWorkload {
  workloadName: string;
  namespace: string;
  workloadKind: string;
  criticalCount: number;
  highCount: number;
  mediumCount: number;
  lowCount: number;
}

export interface TrivyData {
  summary: TrivySummary;
  workloads: TrivyWorkload[];
}

export interface TrivySummary {
  uniqueImagesScanned: number;
  namespacesCovered: number;
  totalCritical: number;
  totalHigh: number;
  totalMedium: number;
  totalLow: number;
  totalExposedSecrets: number;
  lastScanTime?: string;
}

export interface TrivyWorkload {
  name: string;
  namespace: string;
  image: string;
  tag?: string;
  critical: number;
  high: number;
  medium: number;
  low: number;
  lastScanTime?: string;
  vulnerabilities?: TrivyCVE[];
}

export interface TrivyCVE {
  vulnerabilityId: string;
  severity: string;
  title: string;
  packageName: string;
  installedVersion: string;
  fixedVersion?: string;
  description?: string;
  score?: number;
  target?: string;
  primaryLink?: string;
}

export interface KarpenterData {
  healthy: boolean;
  version: string;
  nodePools: NodePool[];
  ec2NodeClasses: EC2NodeClass[];
  provisionedNodes: number;
  pendingPods: number;
  // NEW: Enhanced metrics
  totalActiveClaims: number;
  totalPendingClaims: number;
  claimsPerNodePool?: Record<string, number>;
  nodePoolClassMapping?: Record<string, string[]>;
  spotNodesCount: number;
  onDemandNodesCount: number;
  dailyCostKarpenterNodes?: number;
  nodeClaims?: NodeClaim[];
}

export interface NodePool {
  name: string;
  ready: boolean;
  nodeCount: number;
  cpuLimit: string;
  memoryLimit: string;
}

export interface EC2NodeClass {
  name: string;
  ready: boolean;
  readyMessage?: string;
  amiFamily: string;
  instanceProfile: string;
}

export interface ContainerWithMetrics {
  id: string;
  name: string;
  pod: string;
  namespace: string;
  image: string;
  state: string;
  ready: boolean;
  restartCount: number;
  cpuUsage: number;
  memoryUsage: number;
  fsUsage: number;
}

export interface PotentialSavings {
  daily: number;
  monthly: number;
  affectedNodes: number;
}

export interface QuickWin {
  priority: number;
  type: string;
  icon: string;
  title: string;
  impact: string;
  actions: string[];
}

export interface KarpenterStatus {
  status: 'healthy' | 'degraded' | 'unhealthy';
  label: string;
  color: string;
}

/**
 * The old flat `?tab=` values of the cluster page. Still accepted: `LEGACY_TAB_MAP`
 * (hooks/useEksSectionRoute.ts) maps each to its new section and subtab (design §5.2).
 * `useEksAlertsData` builds alert links with them. `automation` is the realtime branch's tab on the
 * old page (Workload rightsizing).
 */
export type TabType = 'overview' | 'cost' | 'performance' | 'security' | 'workloads' | 'pods' | 'containers' | 'nodes' | 'resources' | 'karpenter' | 'automation' | 'autoscaling';

/** Primary sections of the cluster page, `?tab=` (design §4.2). Only registered ones render. */
export type EksSectionId = 'overview' | 'cost' | 'workloads' | 'nodes' | 'automation' | 'security' | 'data-sources';

/** Subtabs of the cluster page, `?sub=` (design §4.2); the registry says which section owns each. */
export type EksSubtabId =
  | 'summary' | 'events'
  | 'cluster' | 'namespaces' | 'workloads' | 'nodes'
  | 'pods' | 'containers' | 'resources'
  | 'node-groups' | 'karpenter' | 'utilization'
  | 'overview' | 'workload-autoscaling' | 'node-autoscaling' | 'rebalancing' | 'guardrails' | 'activity'
  | 'vulnerabilities' | 'compliance' | 'runtime'
  | 'connections' | 'install';

// ─── XamOps Autoscaling Types (CAST.ai blueprint) ────────────────────────────

export type AutoscalerMode = 'full' | 'scoped' | 'disabled';
export type ApplyMode = 'immediate' | 'deferred';
export type CapacityType = 'spot' | 'on-demand' | 'mixed';

export interface AutoscalerAgentStatus {
  agentHealthy: boolean;
  clusterControllerHealthy: boolean;
  evictorHealthy: boolean;
  workloadAutoscalerHealthy: boolean;
  mode: AutoscalerMode;
  lastSyncAt: string;
  agentVersion: string;
}

export interface ScaleOutPolicy {
  enabled: boolean;
  headroomCpuPercent: number;
  headroomMemoryPercent: number;
  minCpuCores: number;
  maxCpuCores: number;
  minRamMib: number;
  maxRamMib: number;
  nodeReadinessTimeoutMinutes: number;
}

export interface EvictorPolicy {
  enabled: boolean;
  aggressiveMode: boolean;
  nodeCycleIntervalSeconds: number;
  nodeGracePeriodMinutes: number;
  targetUtilizationCpu: number;
  targetUtilizationMemory: number;
}

export interface WorkloadRightsizerPolicy {
  enabled: boolean;
  cpuPercentile: number;
  memoryPercentile: number;
  cpuOverheadPercent: number;
  memoryOverheadPercent: number;
  applyThresholdPercent: number;
  lookbackHours: number;
  applyMode: ApplyMode;
  surgeDetection: boolean;
}

export interface NodeTemplate {
  id: string;
  name: string;
  isDefault: boolean;
  capacityType: CapacityType;
  useSpotFallback: boolean;
  fallbackRestoreRateSeconds: number;
  minCpuCores: number;
  maxCpuCores: number;
  minRamMib: number;
  maxRamMib: number;
  architectures: string[];
  spotAutomationEnabled: boolean;
  customLabels: Record<string, string>;
  customTaints: Array<{ key: string; effect: string }>;
}

export interface RebalancerConfig {
  enabled: boolean;
  gracefulDraining: boolean;
  drainTimeoutMinutes: number;
  scheduleEnabled: boolean;
  scheduleCron: string;
  lastRebalanceAt: string | null;
  lastRebalanceSavingsPercent: number;
  lastRebalanceDuration: number;
  status: 'idle' | 'running' | 'failed' | 'completed';
}

export interface SafetyGuards {
  respectPodDisruptionBudgets: boolean;
  protectSingleReplicas: boolean;
  protectStatefulSets: boolean;
  protectCriticalNamespaces: boolean;
  criticalNamespaces: string[];
}

export interface AutoscalingEvent {
  id: string;
  timestamp: string;
  type: 'scale-out' | 'scale-in' | 'rebalance' | 'workload-resize' | 'spot-fallback' | 'node-replace';
  severity: 'info' | 'warning' | 'success';
  message: string;
  details?: string;
  nodeCount?: number;
  savingsDelta?: number;
}

export interface AutoscalingKpis {
  nodesSavedToday: number;
  costSavedThisWeek: number;
  pendingPodsResolved: number;
  activeRebalances: number;
  spotRatio: number;
  avgNodeUtilizationCpu: number;
  avgNodeUtilizationMemory: number;
  totalManagedNodes: number;
}

// ── Node Optimizer types ──────────────────────────────────────────────────────

export interface NodeOptimizerPolicy {
  enabled: boolean;
  idleCpuThresholdPct: number;
  idleMemThresholdPct: number;
  autoCordonIdleNodes: boolean;
  autoDrainIdleNodes: boolean;
  handleSpotInterruptions: boolean;
  /** Target % of nodes to run as spot (0 = disabled). e.g. 60 → maintain 60% spot. */
  spotRatioTargetPct: number;
  /** Whether Karpenter is installed — enables NodePool CRD patching. */
  karpenterEnabled: boolean;
  /** Max nodes to drain in parallel for spot conversion (default 1). */
  maxParallelDrains: number;
}

export interface IdleNodeInfo {
  nodeName: string;
  instanceType: string;
  cpuUsagePct: number;
  memUsagePct: number;
  podCount: number;
  systemPodsOnly: boolean;
  recommendDrain: boolean;
  capacityType: string;
}

export interface SpotCandidateInfo {
  nodeName: string;
  instanceType: string;
  cpuUsagePct: number;
  memUsagePct: number;
  savingsPct: number;
  interruptible: boolean;
}

export interface NodeFleetReport {
  totalNodes: number;
  onDemandNodes: number;
  spotNodes: number;
  idleNodes: IdleNodeInfo[];
  spotCandidates: SpotCandidateInfo[];
  interruptedNodes: string[];
  estimatedSavingsPct: number;
  collectedAt: string;
}

export interface AutoscalerDashboard {
  autoscalingEnabled: boolean;
  agentStatus: AutoscalerAgentStatus;
  kpis: AutoscalingKpis;
  scaleOutPolicy: ScaleOutPolicy;
  evictorPolicy: EvictorPolicy;
  workloadRightsizerPolicy: WorkloadRightsizerPolicy;
  nodeTemplates: NodeTemplate[];
  rebalancer: RebalancerConfig;
  safetyGuards: SafetyGuards;
  recentEvents: AutoscalingEvent[];
  nodeOptimizerPolicy: NodeOptimizerPolicy;
  nodeFleetReport: NodeFleetReport | null;
}

// NEW: Node Claim interface for Karpenter
export interface NodeClaim {
  name: string;
  nodePoolName: string;
  phase: string;
  instanceType: string;
  capacityType: string;
  zone: string;
  nodeName?: string;
  createdAt?: string;
}

// NEW: Pod Resource Metrics for Pods tab charts
export interface PodResourceMetrics {
  totalCpuRequests: number;
  totalCpuLimits: number;
  totalCpuUsage: number;
  totalCpuCapacity: number;
  totalMemoryRequests: number;
  totalMemoryLimits: number;
  totalMemoryUsage: number;
  totalMemoryCapacity: number;
  podsWithoutRequests: number;
  podsWithoutLimits: number;
}
