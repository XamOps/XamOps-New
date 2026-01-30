// GCP Dashboard Types

export interface GcpKPIData {
  mtdSpend: number;
  forecastedSpend: number;
  lastMonthSpend: number;
  potentialSavings: number;
}

export interface GcpResourceInventory {
  gceInstances: number;
  cloudStorageBuckets: number;
  cloudSqlInstances: number;
  gkeClusters: number;
  vpcNetworks: number;
  cloudDnsZones: number;
  loadBalancers: number;
  cloudNatGateways: number;
  artifactRepositories: number;
  kmsKeys: number;
  cloudFunctions: number;
  cloudBuildTriggers: number;
  secrets: number;
  cloudArmorPolicies: number;
}

export interface GcpRegionStatus {
  region: string;
  latitude: number;
  longitude: number;
  resourceCount: number;
  status: 'active' | 'inactive';
}

export interface GcpCostDataPoint {
  date: string;
  cost: number;
  isAnomaly?: boolean;
  anomalyScore?: number;
}

export interface GcpCostHistory {
  monthlyCosts: GcpCostDataPoint[];
  anomalies: GcpCostAnomaly[];
}

export interface GcpCostAnomaly {
  date: string;
  actualCost: number;
  expectedCost: number;
  anomalyScore: number;
  severity: 'low' | 'medium' | 'high';
  description: string;
}

export interface GcpRightsizingRecommendation {
  resourceId: string;
  resourceName: string;
  resourceType: string;
  currentSize: string;
  recommendedSize: string;
  currentCost: number;
  potentialSavings: number;
  savingsPercentage: number;
  zone: string;
  reason: string;
  confidence: 'low' | 'medium' | 'high';
}

export interface GcpOptimizationData {
  summary: {
    totalRecommendations: number;
    totalPotentialSavings: number;
    highConfidenceRecommendations: number;
  };
  anomalies: GcpCostAnomaly[];
  gceRecommendations: GcpRightsizingRecommendation[];
  cloudSqlRecommendations: GcpRightsizingRecommendation[];
  gkeRecommendations: GcpRightsizingRecommendation[];
}

export interface GcpBillingService {
  serviceName: string;
  cost: number;
  percentage: number;
  trend: 'up' | 'down' | 'stable';
}

export interface GcpBillingSummary {
  topServices: GcpBillingService[];
  totalCost: number;
  serviceCount: number;
}

export interface GcpDashboardData {
  accountId: string;
  accountName: string;
  projectId: string;
  provider: 'GCP';
  kpi: GcpKPIData;
  resourceInventory: GcpResourceInventory;
  regionStatus: GcpRegionStatus[];
  costHistory: GcpCostHistory;
  optimization: GcpOptimizationData;
  billingSummary: GcpBillingSummary;
  lastUpdated: string;
  cacheStatus: 'fresh' | 'stale' | 'expired';
}

export interface GcpMultiAccountDashboardData {
  accounts: GcpDashboardData[];
  aggregated: {
    totalMtdSpend: number;
    totalForecastedSpend: number;
    totalLastMonthSpend: number;
    totalPotentialSavings: number;
    totalResourceCount: number;
    lastUpdated: string;
  };
}

export interface GcpResourceType {
  name: string;
  type: string;
  icon: string;
  count: number;
}

export interface GcpDashboardLoadingState {
  global: boolean;
  kpi: boolean;
  inventory: boolean;
  cost: boolean;
  optimization: boolean;
  map: boolean;
}

export interface GcpDashboardError {
  section: 'kpi' | 'inventory' | 'cost' | 'optimization' | 'map' | 'global';
  message: string;
  timestamp: string;
}

export interface GcpWidgetLayout {
  id: string;
  order: number;
  visible: boolean;
}

export interface GcpDashboardLayout {
  accountId: string;
  widgets: GcpWidgetLayout[];
  lastModified: string;
}

// API Request/Response Types
export interface GcpDashboardApiRequest {
  accountId: string;
  forceRefresh?: boolean;
}

export interface GcpMultiAccountApiRequest {
  accountIds: string[];
}

export interface GcpCacheClearRequest {
  accountId: string;
  type?: 'dashboard' | 'resources' | 'all';
}

export interface GcpDashboardApiResponse {
  success: boolean;
  data?: GcpDashboardData;
  error?: string;
  timestamp: string;
}

export interface GcpHealthCheckResponse {
  status: 'healthy' | 'degraded' | 'down';
  message: string;
  timestamp: string;
}

// WebSocket Message Types
export interface GcpInventoryUpdateMessage {
  accountId: string;
  resourceType: string;
  count: number;
  timestamp: string;
}

// Chart Data Types
export interface GcpMapChartData {
  name: string;
  lat: number;
  lon: number;
  value: number;
}

export interface GcpCostChartData {
  categories: string[];
  series: {
    name: string;
    data: number[];
    anomalyIndexes?: number[];
  }[];
}

export interface GcpBillingChartData {
  labels: string[];
  values: number[];
  colors: string[];
}

// Constants
export const GCP_RESOURCE_ICONS: Record<string, string> = {
  'GCE Instances': '/icons/gcp/gcp-compute-engine.png',
  'Cloud Storage Buckets': '/icons/gcp/gcp-cloud-storage.png',
  'Cloud SQL Instances': '/icons/gcp/gcp-cloud-sql.png',
  'GKE Clusters': '/icons/gcp/gcp-gke.png',
  'VPC Networks': '/icons/gcp/gcp-vpc.png',
  'Cloud DNS Zones': '/icons/gcp/gcp-cloud-dns.png',
  'Load Balancers': '/icons/gcp/gcp-load-balancer.png',
  'Cloud NAT Gateways': '/icons/gcp/gcp-nat.png',
  'Artifact Repositories': '/icons/gcp/gcp-artifact-registry.png',
  'KMS Keys': '/icons/gcp/gcp-kms.png',
  'Cloud Functions': '/icons/gcp/gcp-cloud-functions.png',
  'Cloud Build Triggers': '/icons/gcp/gcp-cloud-build.png',
  'Secrets': '/icons/gcp/gcp-secret-manager.png',
  'Cloud Armor Policies': '/icons/gcp/gcp-cloud-armor.png',
};

export const GCP_REGIONS_MAP: Record<string, { name: string; lat: number; lon: number }> = {
  'us-central1': { name: 'Iowa', lat: 41.8, lon: -93.1 },
  'us-east1': { name: 'South Carolina', lat: 33.8, lon: -81.0 },
  'us-east4': { name: 'N. Virginia', lat: 37.4, lon: -78.6 },
  'us-west1': { name: 'Oregon', lat: 43.8, lon: -120.6 },
  'us-west2': { name: 'Los Angeles', lat: 34.0, lon: -118.2 },
  'us-west3': { name: 'Salt Lake City', lat: 40.8, lon: -111.9 },
  'us-west4': { name: 'Las Vegas', lat: 36.2, lon: -115.1 },
  'europe-west1': { name: 'Belgium', lat: 50.4, lon: 4.4 },
  'europe-west2': { name: 'London', lat: 51.5, lon: -0.1 },
  'europe-west3': { name: 'Frankfurt', lat: 50.1, lon: 8.7 },
  'europe-west4': { name: 'Netherlands', lat: 52.4, lon: 4.9 },
  'europe-west6': { name: 'Zurich', lat: 47.4, lon: 8.5 },
  'asia-east1': { name: 'Taiwan', lat: 24.0, lon: 121.0 },
  'asia-east2': { name: 'Hong Kong', lat: 22.3, lon: 114.2 },
  'asia-northeast1': { name: 'Tokyo', lat: 35.7, lon: 139.8 },
  'asia-northeast2': { name: 'Osaka', lat: 34.7, lon: 135.5 },
  'asia-northeast3': { name: 'Seoul', lat: 37.6, lon: 127.0 },
  'asia-south1': { name: 'Mumbai', lat: 19.1, lon: 72.9 },
  'asia-southeast1': { name: 'Singapore', lat: 1.3, lon: 103.8 },
  'australia-southeast1': { name: 'Sydney', lat: -33.9, lon: 151.2 },
};
