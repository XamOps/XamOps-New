/**
 * Billing Types
 *
 * Type definitions for the Billing Dashboard page
 * Converted from: billops/billing.html
 */

// Cost history item (for bar chart)
export interface CostHistoryItem {
  date: string;
  cost: number;
}

// Service breakdown item (for doughnut chart)
export interface ServiceBreakdownItem {
  name: string;
  cost: number;
}

// Resource within a region
export interface ResourceItem {
  resourceId: string;
  resourceName: string;
  cost: number;
  quantity?: number;
  unit?: string;
}

// Region cost with resources
export interface RegionCost {
  regionName: string;
  cost: number;
  resources: ResourceItem[];
}

// Service breakdown with region details
export interface DetailedServiceBreakdown {
  serviceName: string;
  totalCost: number;
  regionCosts: RegionCost[];
}

// Summary billing data from /api/billops/billing
export interface BillingSummaryData {
  costHistory: CostHistoryItem[];
  serviceBreakdown: ServiceBreakdownItem[];
}

// Month option for filter
export interface MonthOption {
  value: number;
  name: string;
}

// State for expanded services/regions in the table
export interface ExpandedState {
  [key: string]: boolean;
}
