/**
 * FinOps Type Definitions
 *
 * Type definitions for the FinOps Report page
 */

export interface FinOpsKPIs {
  monthToDateSpend: number;
  forecastedSpend: number;
  lastMonthSpend: number;
  potentialSavings: number;
}

export interface CostBreakdownItem {
  service?: string;
  region?: string;
  cost: number;
}

export interface CostBreakdown {
  byService: CostBreakdownItem[];
  byRegion: CostBreakdownItem[];
}

export interface RightsizingRecommendation {
  resourceId: string;
  service: string;
  currentType: string;
  recommendedType: string;
  estimatedMonthlySavings: number;
}

export interface WastedResource {
  resourceId: string;
  resourceType: string;
  region: string;
  reason: string;
  monthlySavings: number;
}

export interface CostAnomaly {
  anomalyId: string;
  service: string;
  startDate: string;
  endDate: string;
  unexpectedSpend: number;
}

export interface UntaggedResource {
  resourceId: string;
  resourceType: string;
  region: string;
}

export interface TaggingCompliance {
  compliancePercentage: number;
  totalResourcesScanned: number;
  untaggedResourcesCount: number;
  untaggedResources: UntaggedResource[];
}

export interface Budget {
  budgetName: string;
  budgetLimit: number;
  budgetUnit: string;
  actualSpend: number;
  forecastedSpend: number;
}

export interface ReportSchedule {
  id: number;
  accountName: string;
  email: string;
  frequency: string;
  cloudAccountId: string;
}

export interface FinOpsReportData {
  kpis: FinOpsKPIs;
  costBreakdown: CostBreakdown;
  rightsizingRecommendations: RightsizingRecommendation[];
  wastedResources: WastedResource[];
  costAnomalies: CostAnomaly[];
  taggingCompliance: TaggingCompliance;
  budgets: Budget[];
}

export interface NewBudget {
  budgetName: string;
  budgetLimit: number | null;
  budgetUnit: string;
}

export interface NewSchedule {
  email: string;
  frequency: string;
  cloudAccountId: string | null;
}

export interface DateFilter {
  startDate: string;
  endDate: string;
}

export interface PaginationState {
  currentPage: number;
  itemsPerPage: number;
}
