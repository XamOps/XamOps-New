/**
 * BillOps User Dashboard Types
 *
 * TypeScript interfaces for the BillOps Command Center user dashboard.
 * Converted from: billops/dashboard_user.html
 */

export interface CloudAccount {
  id: string;
  accountName: string;
  provider: 'AWS' | 'GCP' | 'Azure';
  status: 'ACTIVE' | 'PENDING' | 'INACTIVE';
  currentMonthSpending: number;
  lastMonthSpending: number;
}

export interface Invoice {
  id: string;
  invoiceNumber: string;
  billingPeriod: string;
  amount: number;
  status: 'DRAFT' | 'FINALIZED' | 'PAID' | 'OVERDUE';
}

export interface Ticket {
  id: string;
  subject: string;
  category: string;
  severity?: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  status: 'OPEN' | 'IN_PROGRESS' | 'CLOSED';
}

export interface SpendingTrendItem {
  date: string;
  cost: number;
  costByProvider?: SpendingByProvider;
}

export interface SpendingByProvider {
  AWS?: number;
  GCP?: number;
  Azure?: number;
}

export interface BillOpsUserDashboardData {
  // Metrics
  currentMonthSpending: number;
  lastMonthSpending: number;
  totalAccounts: number;
  awsAccountsCount: number;
  gcpAccountsCount: number;
  azureAccountsCount: number;
  pendingInvoices: number;
  openTickets: number;

  // Charts data
  spendingTrend: SpendingTrendItem[];
  spendingByProvider: SpendingByProvider;

  // Lists
  accounts: CloudAccount[];
  recentInvoices: Invoice[];
  recentTickets: Ticket[];

  // Metadata
  lastUpdated: string;
}

export interface UseBillOpsUserDashboardReturn {
  data: BillOpsUserDashboardData | null;
  loading: boolean;
  isRefreshing: boolean;
  error: string | null;
  refreshDashboard: () => void;
}
