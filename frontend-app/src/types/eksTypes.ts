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
  karpenter: KarpenterData | null;
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
}

export interface NodeCost {
  nodeName: string;
  instanceType: string;
  hourlyRate: number;
  dailyCost: number;
  cpuUsagePercent?: number;
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
  cpuUsage?: number;
  memoryUsage?: number;
}

export interface ContainerStatus {
  name: string;
  image: string;
  state: 'running' | 'waiting' | 'terminated';
  ready: boolean;
  restartCount: number;
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
  namespaceBreakdown: Record<string, NamespaceVulnerabilities>;
  frameworks: FrameworkScore[];
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

export interface KarpenterData {
  healthy: boolean;
  version: string;
  nodePools: NodePool[];
  ec2NodeClasses: EC2NodeClass[];
  provisionedNodes: number;
  pendingPods: number;
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

export type TabType = 'overview' | 'cost' | 'performance' | 'security' | 'workloads' | 'pods' | 'containers' | 'nodes' | 'karpenter';
