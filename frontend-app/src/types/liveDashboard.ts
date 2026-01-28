/**
 * TypeScript types for Live Dashboard (dashboard.html)
 *
 * Converted from: dashboard.html
 *
 * These types match the API responses from the backend live dashboard endpoints.
 * All types preserve exact backend structure - no changes to data shapes.
 */

// ===== KPI Types =====
export interface TrendData {
  labels: string[];
  values: number[];
}

export interface KPIMetric {
  value: number;
  label: string;
  icon: string;
  gradient: string;
  trend?: TrendData;
}

export interface KPIData {
  mtdSpend: number;
  forecastedSpend: number;
  lastMonthSpend: number;
  potentialSavings: number;
  trendData?: {
    mtdSpend: TrendData;
    forecastedSpend: TrendData;
    lastMonthSpend: TrendData;
    potentialSavings: TrendData;
  };
}

// ===== Resource Inventory Types =====
export interface ResourceInventory {
  ec2Count: number;
  s3Count: number;
  rdsCount: number;
  vpcCount: number;
  lambdaCount: number;
  ebsCount: number;
  route53Count: number;
  loadBalancerCount: number;
  ecsCount: number;
  eksCount: number;
  lightsailCount: number;
  amplifyCount: number;
}

export interface ResourceInventoryItem {
  name: string;
  count: number;
  icon: string;
  service: string; // For navigation filter
  color: string;
}

// ===== Region/Map Types =====
export type RegionStatusType = 'ACTIVE' | 'SEMI_ACTIVE' | 'SUSTAINABLE' | 'NON_ACTIVE';

// API response format (matches original dashboard.html)
export interface RegionStatus {
  regionId: string;        // e.g., "us-east-1"
  latitude: number;
  longitude: number;
  status: RegionStatusType;
  // Optional fields that may or may not be present
  regionName?: string;     // e.g., "US East (N. Virginia)"
  resourceCount?: number;
}

// ===== Cost History Types =====
export interface CostAnomaly {
  month?: string;
  service?: string;
  startDate?: string;
  percentChange?: number;
  previousCost?: number;
  currentCost?: number;
  unexpectedSpend?: number;
}

export interface CostHistory {
  labels: string[]; // Month labels (e.g., ["Aug", "Sep", "Oct", ...])
  costs: number[]; // Cost values
  anomalies: boolean[] | CostAnomaly[]; // Boolean array for chart coloring OR detailed anomaly objects
}

// ===== Billing Summary Types =====
export interface ServiceCost {
  serviceName: string;
  cost: number;
  percentage: number;
}

export interface BillingSummary {
  services: ServiceCost[];
  totalCost: number;
}

// ===== Optimization Types =====
export interface EC2Recommendation {
  instanceId: string;
  instanceType: string;
  currentCost: number;
  recommendedType: string;
  estimatedSavings: number;
  reason: string;
}

export interface EBSRecommendation {
  volumeId: string;
  volumeType: string;
  size: number;
  currentCost: number;
  recommendation: string;
  estimatedSavings: number;
}

export interface LambdaRecommendation {
  functionName: string;
  currentMemory: number;
  recommendedMemory: number;
  currentCost: number;
  estimatedSavings: number;
  reason: string;
}

export interface OptimizationSummary {
  totalRecommendations: number;
  totalPotentialSavings: number;
  criticalAlerts: number;
  ec2RecommendationCount: number;
  ebsRecommendationCount: number;
  lambdaRecommendationCount: number;
}

export interface OptimizationData {
  summary: OptimizationSummary;
  anomalies: CostAnomaly[];
  ec2Recommendations: EC2Recommendation[];
  ebsRecommendations: EBSRecommendation[];
  lambdaRecommendations: LambdaRecommendation[];
}

// ===== Main Dashboard Data Types =====
export interface DashboardAccount {
  accountId: string;
  accountName: string;
  provider: 'AWS' | 'GCP' | 'Azure';
  kpi: KPIData;
  resourceInventory: ResourceInventory;
  regionStatus: RegionStatus[];
  costHistory: CostHistory;
  optimization: OptimizationData;
  billingSummary: BillingSummary;
  lastUpdated: string;
}

export interface MultiAccountDashboardData {
  accounts: DashboardAccount[];
  aggregated?: DashboardAccount; // For multi-account view
}

// ===== Granular Endpoint Response Types =====
export interface GranularKPIResponse {
  accountId: string;
  kpi: KPIData;
}

export interface GranularInventoryResponse {
  accountId: string;
  resourceInventory: ResourceInventory;
}

export interface GranularMapResponse {
  accountId: string;
  regionStatus: RegionStatus[];
}

export interface GranularCostResponse {
  accountId: string;
  costHistory: CostHistory;
  billingSummary: BillingSummary;
}

export interface GranularOptimizationResponse {
  accountId: string;
  optimization: OptimizationData;
}

// ===== Layout Types =====
export interface WidgetLayout {
  widgetOrder: string[]; // Array of widget IDs: ["widget-kpi", "widget-map", ...]
}

// ===== Loading State Types =====
export interface LoadingState {
  global: boolean;
  kpi: boolean;
  inventory: boolean;
  cost: boolean;
  optimization: boolean;
  map: boolean;
}

// ===== Chart Configuration Types =====
export interface ChartColor {
  primary: string;
  secondary: string;
  gradient?: {
    start: string;
    end: string;
  };
}

export interface HighchartsMapPoint {
  lat: number;
  lon: number;
  name: string;
  code: string;
  color: string;
  marker?: {
    radius?: number;
    fillColor?: string;
  };
}

// ===== WebSocket Types =====
export interface WebSocketMessage {
  type: 'INVENTORY_UPDATE';
  accountId: string;
  data: ResourceInventory;
}

// ===== Error Types =====
export interface DashboardError {
  message: string;
  code?: string;
  timestamp?: string;
}
