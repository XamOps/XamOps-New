/**
 * BillOps Admin Dashboard Types
 *
 * TypeScript interfaces for the BillOps Admin Command Center dashboard.
 * Converted from: billops/dashboard_admin.html
 */

export interface TopSpendingAccount {
  id: string;
  accountName: string;
  provider: 'AWS' | 'GCP' | 'Azure';
  clientName: string;
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
  status: 'DRAFT' | 'FINALIZED' | 'PAID' | 'OVERDUE';
}

export interface AdminTicket {
  id: string;
  subject: string;
  category: string;
  severity?: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  status: 'OPEN' | 'IN_PROGRESS' | 'CLOSED';
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
