/**
 * GKE (Google Kubernetes Engine) Types
 *
 * Type definitions for GKE clusters and related data structures
 */

export interface GKECluster {
  name: string;
  location: string; // GCP uses 'location' (can be zone or region)
  zone?: string; // For zonal clusters
  region?: string; // For regional clusters
  version: string;
  connected: boolean;
  isEnabling?: boolean;
  status?: string;
  nodeCount?: number;
  currentNodeVersion?: string;
  endpoint?: string;
  masterAuthorizedNetworksEnabled?: boolean;
  loggingService?: string;
  monitoringService?: string;
}

export interface GKEClusterDetails {
  name: string;
  location: string;
  version: string;
  status: string;
  nodeCount: number;
  endpoint: string;
  createdAt?: string;
  description?: string;
  network?: string;
  subnetwork?: string;
  clusterIpv4Cidr?: string;
  servicesIpv4Cidr?: string;
}

export interface GKENodePool {
  name: string;
  version: string;
  status: string;
  nodeCount: number;
  instanceType: string;
  diskSize: number;
  autoscaling?: {
    enabled: boolean;
    minNodeCount: number;
    maxNodeCount: number;
  };
  management?: {
    autoUpgrade: boolean;
    autoRepair: boolean;
  };
}

export interface GKEWorkload {
  name: string;
  namespace: string;
  kind: string;
  replicas?: number;
  availableReplicas?: number;
  createdAt?: string;
}

export interface GKEPod {
  name: string;
  namespace: string;
  status: string;
  nodeName?: string;
  startTime?: string;
  containerStatuses?: GKEContainerStatus[];
}

export interface GKEContainerStatus {
  name: string;
  image: string;
  ready: boolean;
  restartCount: number;
  state: string;
}

export interface GKEMetrics {
  cpuUsage?: number;
  memoryUsage?: number;
  diskUsage?: number;
  networkIn?: number;
  networkOut?: number;
}

// --- GKE Dashboard Types (mirrors EKS dashboard structure) ---

export type GkeTabType = 'overview' | 'cost' | 'performance' | 'security' | 'workloads' | 'pods' | 'containers' | 'nodes';

export interface GkeDashboard {
  clusterName: string;
  location: string;
  status: string;
  version: string;
  endpoint: string;
  connected: boolean;
  lastSyncedAt?: string;
  overview?: GkeOverview;
  nodePools: GkeNodePoolInfo[];
  workloadHealth?: GkeWorkloadHealth;
  costMetrics?: GkeCostMetrics;
  resourceUtilization?: GkeResourceUtilization;
  nodeMetrics?: GkeNodeMetrics;
  containerMetrics?: GkeContainerMetrics;
  securityMetrics?: GkeSecurityMetrics;
}

export interface GkeOverview {
  totalNodes: number;
  totalPods: number;
  totalNamespaces: number;
  cpuUsageMillicores: number;
  cpuCapacityMillicores: number;
  cpuUsagePercent: number;
  memoryUsageBytes: number;
  memoryCapacityBytes: number;
  memoryUsagePercent: number;
}

export interface GkeNodePoolInfo {
  name: string;
  machineType: string;
  nodeCount: number;
  version: string;
  autoscalingEnabled: boolean;
  minNodes: number;
  maxNodes: number;
  status: string;
  diskSizeGb: string;
  imageType: string;
}

export interface GkeWorkloadHealth {
  pods: GkePodInfo[];
  deployments: GkeDeploymentInfo[];
  statefulsets: GkeWorkloadInfo[];
  daemonsets: GkeWorkloadInfo[];
  clusterHealthScore: number;
}

export interface GkePodInfo {
  name: string;
  namespace: string;
  phase: string;
  nodeName: string;
  restartCount: number;
  cpuUsage: number;
  memoryUsage: number;
  cpuRequest: number;
  memoryRequest: number;
  age: string;
  podIp?: string;
  ownerKind?: string;
  ownerName?: string;
}

export interface GkeDeploymentInfo {
  name: string;
  namespace: string;
  replicas: number;
  availableReplicas: number;
  readyReplicas: number;
}

export interface GkeWorkloadInfo {
  name: string;
  namespace: string;
  replicas: number;
  readyReplicas: number;
  kind: string;
}

// --- Cost Metrics ---

export interface GkeCostMetrics {
  dailyCost: number;
  namespaceCosts: GkeNamespaceCost[];
  workloadCosts: GkeWorkloadCost[];
}

export interface GkeNamespaceCost {
  namespace: string;
  cpuUsageMillicores: number;
  memoryUsageMb: number;
  podCount: number;
  dailyCost: number;
  monthlyCost: number;
}

export interface GkeWorkloadCost {
  name: string;
  namespace: string;
  kind: string;
  cpuUsageMillicores: number;
  memoryUsageMb: number;
  podCount: number;
  dailyCost: number;
  monthlyCost: number;
}

// --- Resource Utilization (per-node) ---

export interface GkeResourceUtilization {
  nodes: GkeNodeResource[];
}

export interface GkeNodeResource {
  nodeName: string;
  cpuUsagePercent: number;
  memoryUsagePercent: number;
  cpuUsageMillicores: number;
  cpuCapacityMillicores: number;
  cpuRequestsPercent: number;
  memoryRequestsPercent: number;
}

// --- Node Metrics (conditions + info) ---

export interface GkeNodeMetrics {
  nodes: GkeNodeInfo[];
}

export interface GkeNodeInfo {
  name: string;
  ready: boolean;
  diskPressure: boolean;
  memoryPressure: boolean;
  instanceType: string;
  zone: string;
  internalIp: string;
  kubeletVersion: string;
  nodePool: string;
  cpuCapacity: number;
  memoryCapacity: number;
  cpuUsage: number;
  cpuUsageMillicores: number;
  memoryUsage: number;
  podCount: number;
}

// --- Container Metrics ---

export interface GkeContainerMetrics {
  containers: GkeContainer[];
}

export interface GkeContainer {
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

// --- Security Metrics (Falco) ---

export interface GkeSecurityMetrics {
  totalEvents: number;
  eventsByPriority: Record<string, number>;
  alerts: GkeSecurityAlert[];
}

export interface GkeSecurityAlert {
  rule: string;
  priority: string;
  count: number;
  lastSeen: string;
  namespace: string;
}
