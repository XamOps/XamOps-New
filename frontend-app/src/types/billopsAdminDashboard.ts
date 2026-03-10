/**
 * BillOps Admin Dashboard Types
 *
 * TypeScript interfaces for the BillOps Admin Command Center dashboard.
 * Converted from: billops/dashboard_admin.html
 */

export interface TopSpendingAccount {
  id: string;
  accountName: string;
  accountIdentifier?: string;
  provider: 'AWS' | 'GCP' | 'Azure';
  clientName: string;
  clientId?: number;
  status: 'ACTIVE' | 'PENDING' | 'INACTIVE';
  currentMonthSpending: number;
  lastMonthSpending: number;
}

export interface AdminInvoice {
  id: string;
  invoiceNumber: string;
  clientName: string;
  accountName: string;
  amount: number;
  provider?: 'AWS' | 'GCP' | 'Azure';
  status: 'DRAFT' | 'FINALIZED' | 'PAID' | 'OVERDUE';
}

export interface AdminTicket {
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

export interface AccountsByStatus {
  ACTIVE?: number;
  PENDING?: number;
  INACTIVE?: number;
}

export interface BillOpsAdminDashboardData {
  // Metrics
  totalSpending: number;
  totalAccounts: number;
  awsAccountsCount: number;
  gcpAccountsCount: number;
  azureAccountsCount: number;
  pendingInvoices: number;
  finalizedInvoices: number;
  openTickets: number;
  totalClients: number;

  // Charts data
  spendingTrend: SpendingTrendItem[];
  spendingByProvider: SpendingByProvider;
  accountsByStatus: AccountsByStatus;

  // Lists
  topSpendingAccounts: TopSpendingAccount[];
  recentInvoices: AdminInvoice[];
  recentTickets: AdminTicket[];

  // Metadata
  lastUpdated: string;
}

export interface UseBillOpsAdminDashboardReturn {
  data: BillOpsAdminDashboardData | null;
  loading: boolean;
  isRefreshing: boolean;
  error: string | null;
  refreshDashboard: () => void;
}
