/**
 * Azure FinOps Type Definitions
 *
 * Type definitions for the Azure FinOps Report page
 */

/**
 * Every figure is nullable because the backend now distinguishes "not measured" from zero.
 *
 * These are read from the billing-ingestion cache, so a subscription whose ingestion has not run
 * yet has no spend figure at all. Rendering that as 0 told the user they had spent nothing and
 * their costs were flat. Treat null as "not available" — do not `?? 0` it.
 */
export interface AzureFinOpsKPIs {
  totalSpend: number | null;
  forecastedSpend: number | null;
  /** Null until 13 months of history exist. */
  yoyChange: number | null;
  /** Null until two closed months exist. */
  momChange: number | null;
}

export interface AzureCostBreakdownItem {
  service: string;
  cost: number;
  /** Month over month change. Null when the service has no prior closed month to compare against. */
  change: number | null;
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
  /** Why one or more KPI figures are null, or absent when every figure was measured. */
  kpisUnavailableReason?: string | null;
  costBreakdown: AzureCostBreakdownItem[];
  costByRegion: AzureCostByRegionItem[];
  totalPotentialSavings: number;
  rightsizingItems: AzureFinOpsRightsizingItem[];
  wasteItems: AzureFinOpsWasteItem[];
  // Legacy fields kept for backward compatibility
  rightsizingRecommendations?: AzureRightsizingRecommendation[];
  wastedResources?: AzureWastedResource[];
  costAnomalies?: AzureCostAnomaly[];
  /**
   * Why costAnomalies is empty, or null when detection ran normally.
   *
   * <p>An empty list means either "spend looks normal" or "we never checked". Detection legitimately
   * cannot run for the first week of a month on subscriptions billed through a month-to-date cost
   * export, so treating an empty list as a clean bill of health is wrong most of the time.
   */
  costAnomaliesUnavailableReason?: string | null;
  budgets?: AzureBudget[];
  /** Why budgets is empty, or null when Azure returned the list normally. */
  budgetsUnavailableReason?: string | null;
}

export interface AzureNewBudget {
  budgetName: string;
  budgetLimit: number | null;
  /**
   * Addresses to alert when the budget is exceeded. Optional, but without at least one the budget
   * cannot notify anyone: Azure requires a contact address on a subscription-scoped budget
   * notification, and contactRoles alone is not accepted.
   */
  contactEmails?: string[];
  budgetUnit: string;
}

export interface AzureNewSchedule {
  email: string;
  frequency: string;
  cloudAccountId: string | null;
}
