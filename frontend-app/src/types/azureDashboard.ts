// Azure Dashboard Types

export interface AzureKPIData {
  mtdSpend: number;
  forecastedSpend: number;
  lastMonthSpend: number;
  potentialSavings: number;
}

export interface AzureResourceInventory {
  virtualMachines: number;
  storageAccounts: number;
  sqlDatabases: number;
  virtualNetworks: number;
  functions: number;
  disks: number;
  dnsZones: number;
  loadBalancers: number;
  containerInstances: number;
  kubernetesServices: number;
  appServices: number;
  staticWebApps: number;
}

export interface AzureRegionStatus {
  name: string;
  latitude: number;
  longitude: number;
  status: 'ACTIVE' | 'SEMI_ACTIVE' | 'SUSTAINABLE' | 'NON_ACTIVE';
}

export interface AzureCostDataPoint {
  date: string;
  cost: number;
  isAnomaly?: boolean;
}

export interface AzureCostHistory {
  labels: string[];
  costs: number[];
  anomalies: boolean[];
}

export interface AzureCostAnomaly {
  service: string;
  startDate: string;
  unexpectedSpend: number;
}

export interface AzureVMRecommendation {
  resourceId: string;
  currentType: string;
  recommendedType: string;
  estimatedMonthlySavings: number;
  currentMonthlyCost: number;
}

export interface AzureDiskRecommendation {
  resourceId: string;
  currentType: string;
  recommendedType: string;
  estimatedMonthlySavings: number;
  currentMonthlyCost: number;
}

export interface AzureFunctionRecommendation {
  resourceId: string;
  currentType: string;
  recommendedType: string;
  estimatedMonthlySavings: number;
  currentMonthlyCost: number;
}

export interface AzureOptimizationSummary {
  totalPotentialSavings: number;
  criticalAlertsCount: number;
}

export interface AzureOptimizationData {
  summary: AzureOptimizationSummary;
  vmRecommendations: AzureVMRecommendation[];
  diskRecommendations: AzureDiskRecommendation[];
  functionRecommendations: AzureFunctionRecommendation[];
  costAnomalies: AzureCostAnomaly[];
}

export interface AzureBillingItem {
  serviceName: string;
  monthToDateCost: number;
}

export interface AzureDashboardData {
  accountId: string;
  accountName: string;
  provider: 'Azure';
  kpi: AzureKPIData;
  resourceInventory: AzureResourceInventory;
  regionStatus: AzureRegionStatus[];
  costHistory: AzureCostHistory;
  optimization: AzureOptimizationData;
  billingSummary: AzureBillingItem[];
  costByRegion: Record<string, number>;
  optimizationSummary: AzureOptimizationSummary;
  vmRecommendations: AzureVMRecommendation[];
  diskRecommendations: AzureDiskRecommendation[];
  functionRecommendations: AzureFunctionRecommendation[];
  costAnomalies: AzureCostAnomaly[];
  lastUpdated: string;
  cacheStatus: 'fresh' | 'stale' | 'expired';
}

export interface AzureResourceType {
  name: string;
  type: string;
  icon: string;
  count: number;
}

export interface AzureDashboardLoadingState {
  global: boolean;
  kpi: boolean;
  inventory: boolean;
  cost: boolean;
  optimization: boolean;
  map: boolean;
}

export interface AzureDashboardError {
  section: 'kpi' | 'inventory' | 'cost' | 'optimization' | 'map' | 'global';
  message: string;
  timestamp: string;
}

// Constants
export const AZURE_RESOURCE_ICONS: Record<string, string> = {
  'Virtual Machines': '/icons/virtual-machine.png',
  'Storage Accounts': '/icons/azure-storage-account.png',
  'SQL Databases': '/icons/sql-database.png',
  'Virtual Networks': '/icons/virtual-network.png',
  'Function Apps': '/icons/function-app.png',
  'Disk Storage': '/icons/disk-storage.png',
  'DNS Zones': '/icons/dns.png',
  'Load Balancers': '/icons/load-balancer.png',
  'Container Instances': '/icons/container-instance.png',
  'Kubernetes Services': '/icons/azure-aks.png',
  'App Services': '/icons/app-service.png',
  'Static Web Apps': '/icons/static-web-app.png',
};
