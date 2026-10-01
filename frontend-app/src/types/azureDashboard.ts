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
  /**
   * Per-point flag marking a label as a forecast rather than actual spend.
   *
   * <p>Named `anomalies` on the wire until now, which it never was: the backend sets it true for the
   * projected "MMM yyyy (Forecast)" point, and the FinOps KPIs sum those to produce forecast spend.
   * Real anomalies are the separate `costAnomalies` list. The API layer accepts the old key so a
   * cached payload still renders.
   */
  forecast: boolean[];
}

export interface AzureCostAnomaly {
  /** Stable identifier, derived from subscription + service + date. */
  anomalyId?: string;
  service: string;
  startDate: string;
  endDate?: string;
  /** Spend above the trailing 7-day average for that service, not the whole day's cost. */
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

  /**
   * False when at least one section could not be populated, so the zeros in this payload are
   * placeholders rather than measurements.
   *
   * <p>The backend has always set this; nothing rendered it, which is how a subscription with no
   * cost export configured came to display a confident $0.00 spend.
   */
  dataComplete?: boolean;

  /** Customer-facing reasons {@link dataComplete} is false, each naming what to do about it. */
  dataWarnings?: string[];

  /**
   * ISO code of the currency every cost in this payload is denominated in, e.g. `"INR"`.
   *
   * <p>Undefined when the backend has not ingested a currency for the subscription yet, which is
   * deliberately distinguishable from `"USD"`: a caller that cannot tell them apart is how the
   * hardcoded rupee symbol got there in the first place.
   */
  currency?: string;

  /** The same month-to-date figure in USD, from Azure's own conversion rather than a local rate. */
  monthToDateSpendUsd?: number;

  /** How old the snapshot behind these figures is, so the page can say rather than imply. */
  snapshotAgeMinutes?: number;

  /** True when the snapshot is old enough that the page should say so. */
  stale?: boolean;
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
