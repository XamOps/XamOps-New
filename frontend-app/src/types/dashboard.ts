/**
 * TypeScript types for Unified Dashboard
 *
 * These types match the API responses from the backend unified dashboard endpoints
 */

export interface ResourceInventory {
  totalCount: number;
  ec2Count?: number;
  s3Count?: number;
  rdsCount?: number;
}

export interface OptimizationSummary {
  criticalAlerts: number;
  totalPotentialSavings: number;
}

export interface CostHistory {
  labels: string[];
  costs: number[];
}

export interface SubAccount {
  id: string;
  name: string;
  provider?: string;
  monthToDateSpend: number;
  forecastedSpend: number;
  resourceInventory?: ResourceInventory;
  resourceCount?: number;
  optimizationSummary?: OptimizationSummary;
  securityScore?: number;
}

export interface SelectedAccount {
  monthToDateSpend: number;
  forecastedSpend: number;
  resourceInventory: ResourceInventory;
  optimizationSummary: OptimizationSummary;
  securityScore: number;
  costHistory: CostHistory;
  subAccounts: SubAccount[];
  awsAccountCount?: number;
  gcpAccountCount?: number;
  azureAccountCount?: number;
}

export interface ProviderData {
  selectedAccount: SelectedAccount;
  lastUpdated?: string;
  error?: string;
}

export interface SummaryData {
  selectedAccount: {
    awsAccountCount: number;
    gcpAccountCount: number;
    azureAccountCount: number;
  };
}

export interface HealthData {
  // Add health-specific fields as needed
  status?: string;
}

export interface DashboardErrors {
  summary: string | null;
  aws: string | null;
  gcp: string | null;
  azure: string | null;
  health: string | null;
  tickets: string | null;
}

export interface DashboardLoading {
  summary: boolean;
  aws: boolean;
  gcp: boolean;
  azure: boolean;
  health: boolean;
  tickets: boolean;
}

export type ViewMode = 'cost' | 'resource' | 'waste' | 'alerts' | 'savings';

export type CloudProvider = 'AWS' | 'GCP' | 'Azure';
