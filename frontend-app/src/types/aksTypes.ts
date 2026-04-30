export interface AKSCluster {
  name: string;
  resourceGroup: string;
  location: string;
  kubernetesVersion: string;
  provisioningState?: string;
  nodeCount?: number;
  connected: boolean;
}

// AKS Dashboard — same shape as GkeDashboard (reuses GKE types for frontend components)
// plus Azure-specific resourceGroup field
import type { GkeDashboard } from './gkeTypes';

export interface AksDashboard extends GkeDashboard {
  resourceGroup?: string;
}
