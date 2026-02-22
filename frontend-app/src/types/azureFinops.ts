/**
 * Azure FinOps Type Definitions
 *
 * Type definitions for the Azure FinOps Report page
 */

export interface AzureFinOpsKPIs {
  totalSpend: number;
  forecastedSpend: number;
  yoyChange: number;
  momChange: number;
}

export interface AzureCostBreakdownItem {
  service: string;
  cost: number;
  change: number; // Month over month change
}

export interface AzureCostByRegionItem {
  region: string;
  cost: number;
}

export interface AzureRightsizingRecommendation {
  resourceId: string;
  service: string;
  currentType: string;
  recommendedType: string;
  estimatedMonthlySavings: number;
}

export interface AzureWastedResource {
  resourceId: string;
  resourceType: string;
  region: string;
  reason: string;
  monthlySavings: number;
}

export interface AzureCostAnomaly {
  anomalyId: string;
  service: string;
  startDate: string;
  endDate: string;
  unexpectedSpend: number;
}

export interface AzureBudget {
  budgetName: string;
  budgetLimit: number;
  budgetUnit: string;
  actualSpend: number;
  forecastedSpend: number;
}

export interface AzureReportSchedule {
  id: number;
  accountName?: string;
  subscriptionName?: string;
  email: string;
  frequency: string;
  cloudAccountId: string;
}

// New types matching the updated backend DTO
export interface AzureFinOpsRightsizingItem {
  resourceName: string;
  resourceType: string;
  currentSku: string;
  targetSku: string;
  monthlySavings: number;
  annualSavings: number;
  impact: string;
  location: string;
}

export interface AzureFinOpsWasteItem {
  resourceId: string;
  resourceName: string;
  resourceType: string;
  region: string;
  reason: string;
  monthlySavings: number;
}

export interface AzureFinOpsReportData {
  kpis: AzureFinOpsKPIs;
  costBreakdown: AzureCostBreakdownItem[];
  costByRegion: AzureCostByRegionItem[];
  totalPotentialSavings: number;
  rightsizingItems: AzureFinOpsRightsizingItem[];
  wasteItems: AzureFinOpsWasteItem[];
  // Legacy fields kept for backward compatibility
  rightsizingRecommendations?: AzureRightsizingRecommendation[];
  wastedResources?: AzureWastedResource[];
  costAnomalies?: AzureCostAnomaly[];
  budgets?: AzureBudget[];
}

export interface AzureNewBudget {
  budgetName: string;
  budgetLimit: number | null;
  budgetUnit: string;
}

export interface AzureNewSchedule {
  email: string;
  frequency: string;
  cloudAccountId: string | null;
}
