/**
 * GCP FinOps Type Definitions
 *
 * Type definitions for the GCP FinOps Report page
 */

export interface GcpFinOpsKPIs {
  monthToDateSpend: number;
  forecastedSpend: number;
  lastMonthSpend: number;
  potentialSavings: number;
}

export interface GcpCostBreakdownItem {
  name: string;
  amount: number;
}

export interface GcpCostBreakdown {
  byService: GcpCostBreakdownItem[];
  byRegion: GcpCostBreakdownItem[];
}

export interface GcpRightsizingRecommendation {
  recommendationId?: string;
  resourceName: string;
  service: string;
  location?: string;
  currentMachineType: string;
  recommendedMachineType: string;
  recommendationType: 'COST_SAVINGS' | 'PERFORMANCE_IMPROVEMENT';
  monthlySavings: number;
}

export interface GcpWastedResource {
  resourceId: string;
  resourceType: string;
  region: string;
  reason: string;
  monthlySavings: number;
}

export interface GcpCostAnomaly {
  anomalyId: string;
  service: string;
  startDate: string;
  endDate: string;
  unexpectedSpend: number;
}

export interface GcpBudget {
  budgetName: string;
  budgetLimit: number;
  currencyCode: string;
  actualSpend: number;
  forecastedSpend: number;
}

export interface GcpReportSchedule {
  id: number;
  accountName: string;
  email: string;
  frequency: string;
  cloudAccountId: string;
  customHour?: number;
  customMinute?: number;
  customDayOfWeek?: number;
  customDayOfMonth?: number;
  customFrequencyType?: string;
}

export interface GcpFinOpsReportData {
  kpis: GcpFinOpsKPIs;
  costBreakdown: GcpCostBreakdown;
  rightsizingRecommendations: GcpRightsizingRecommendation[];
  wastedResources: GcpWastedResource[];
  costAnomalies: GcpCostAnomaly[];
  budgets: GcpBudget[];
}

export interface GcpNewBudget {
  budgetName: string;
  budgetLimit: number | null;
  currencyCode: string;
}

export interface GcpNewSchedule {
  email: string;
  frequency: string;
  cloudAccountId: string | null;
  customHour?: number;
  customMinute?: number;
  customDayOfWeek?: number;
  customDayOfMonth?: number;
  customFrequencyType?: string;
}

export interface DateFilter {
  startDate: string;
  endDate: string;
}
