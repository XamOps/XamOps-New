/**
 * GCP Billing Types
 *
 * Type definitions for the GCP Billing Dashboard page
 * Converted from: billops/gcp-billing.html
 */

// Cost history item (for line chart)
export interface GcpCostHistoryItem {
  date?: string;
  month?: string;
  name?: string;
  amount: number;
  cost?: number;
}

// Service breakdown item (for doughnut chart and table)
export interface GcpServiceBreakdownItem {
  serviceName: string;
  name?: string;
  amount: number;
}

// Summary billing data from /api/billops/billing/gcp/dashboard
export interface GcpBillingSummaryData {
  totalCost: number;
  costThisMonth: number;
  costLastMonth: number;
  costHistory: GcpCostHistoryItem[];
  serviceBreakdown: GcpServiceBreakdownItem[];
}

// Month option for filter
export interface GcpMonthOption {
  value: string;
  label: string;
}

// State for expanded services in the table
export interface GcpExpandedState {
  [key: string]: boolean;
}
