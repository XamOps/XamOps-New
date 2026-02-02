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
