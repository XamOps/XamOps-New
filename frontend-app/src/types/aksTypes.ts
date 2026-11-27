export interface AKSCluster {
  name: string;
  resourceGroup: string;
  location: string;
  kubernetesVersion: string;
  provisioningState?: string;
  /** "Running" or "Stopped". A stopped cluster still reports provisioningState "Succeeded". */
  powerState?: string;
  nodeCount?: number;
  connected: boolean;
  /** The agent has pushed data before but has not checked in within the last 15 minutes. */
  agentStale?: boolean;
}

// AKS Dashboard — same shape as GkeDashboard (reuses GKE types for frontend components)
// plus Azure-specific resourceGroup field
import type { GkeDashboard } from './gkeTypes';

export interface AksDashboard extends GkeDashboard {
  resourceGroup?: string;
  /** ISO code of the cost figures (the subscription's billing currency). Absent on older cached copies, which are USD. */
  currency?: string;
  /** When the XamOps agent last checked in. */
  agentLastSeenAt?: string;
  /** The figures come from the agent, but it has not checked in within the last 15 minutes. */
  agentStale?: boolean;
  /** Why this refresh could not complete; the dashboard shown is then the last complete copy. */
  refreshError?: string;
}
