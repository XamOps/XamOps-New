/**
 * BillOps Admin Demo Mock Data  (ROLE_BILLOPS_ADMIN)
 *
 * Realistic-looking but fully static data returned for every API call
 * when the demo user with ROLE_BILLOPS_ADMIN is logged in.
 */

import type { BillOpsAdminDashboardData, TopSpendingAccount } from '../types/billopsAdminDashboard';

// ─── /api/billops/profile  (identity + role, returned as-is from backend) ────
// Not mocked — the backend serves the real profile for auth to work.

// ─── /api/billops/team/profile ───────────────────────────────────────────────
export const demoBillopsTeamProfile = {
  isPrimaryUser: true,
  allowedTabs: ['dashboard', 'invoices', 'credits', 'tickets', 'agreements', 'clients'],
};

// ─── /api/billops/dashboard/admin ────────────────────────────────────────────
export const demoBillopsAdminDashboard: BillOpsAdminDashboardData = {
  totalSpending: 284750.40,
  totalAccounts: 18,
  awsAccountsCount: 11,
  gcpAccountsCount: 4,
  azureAccountsCount: 3,
  pendingInvoices: 6,
  finalizedInvoices: 14,
  openTickets: 9,
  totalClients: 7,

  spendingTrend: [
    { date: '2025-11', cost: 218400, costByProvider: { AWS: 138000, GCP: 48200, Azure: 32200 } },
    { date: '2025-12', cost: 241600, costByProvider: { AWS: 154000, GCP: 52400, Azure: 35200 } },
    { date: '2026-01', cost: 228900, costByProvider: { AWS: 146200, GCP: 49600, Azure: 33100 } },
    { date: '2026-02', cost: 256100, costByProvider: { AWS: 162400, GCP: 55800, Azure: 37900 } },
    { date: '2026-03', cost: 271300, costByProvider: { AWS: 172100, GCP: 59400, Azure: 39800 } },
    { date: '2026-04', cost: 284750, costByProvider: { AWS: 181200, GCP: 62300, Azure: 41250 } },
  ],

  spendingByProvider: { AWS: 181200, GCP: 62300, Azure: 41250 },

  accountsByStatus: { ACTIVE: 14, PENDING: 2, INACTIVE: 2 },

  topSpendingAccounts: [
    {
      id: 'acc-001', accountName: 'Apex Corp Production', accountIdentifier: '123456789012',
      provider: 'AWS', clientName: 'Apex Corp', clientId: 1,
      status: 'ACTIVE', currentMonthSpending: 52400, lastMonthSpending: 48600,
    },
    {
      id: 'acc-002', accountName: 'NexGen Cloud Main', accountIdentifier: '234567890123',
      provider: 'AWS', clientName: 'NexGen Solutions', clientId: 2,
      status: 'ACTIVE', currentMonthSpending: 38900, lastMonthSpending: 36200,
    },
    {
      id: 'acc-003', accountName: 'DataVault GCP', accountIdentifier: 'proj-datavault-prod',
      provider: 'GCP', clientName: 'DataVault Inc', clientId: 3,
      status: 'ACTIVE', currentMonthSpending: 29700, lastMonthSpending: 27400,
    },
    {
      id: 'acc-004', accountName: 'CloudBase Azure East', accountIdentifier: 'sub-cloudbase-001',
      provider: 'Azure', clientName: 'CloudBase Ltd', clientId: 4,
      status: 'ACTIVE', currentMonthSpending: 24100, lastMonthSpending: 22800,
    },
    {
      id: 'acc-005', accountName: 'Sigma Tech AWS Staging', accountIdentifier: '345678901234',
      provider: 'AWS', clientName: 'Sigma Tech', clientId: 5,
      status: 'ACTIVE', currentMonthSpending: 18600, lastMonthSpending: 17300,
    },
  ] as TopSpendingAccount[],

  recentInvoices: [
    { id: 'inv-001', invoiceNumber: 'INV-2026-041', clientName: 'Apex Corp',       accountName: 'Apex Corp Production',    amount: 52400, provider: 'AWS',   status: 'FINALIZED' },
    { id: 'inv-002', invoiceNumber: 'INV-2026-042', clientName: 'NexGen Solutions', accountName: 'NexGen Cloud Main',        amount: 38900, provider: 'AWS',   status: 'DRAFT' },
    { id: 'inv-003', invoiceNumber: 'INV-2026-043', clientName: 'DataVault Inc',    accountName: 'DataVault GCP',            amount: 29700, provider: 'GCP',   status: 'PAID' },
    { id: 'inv-004', invoiceNumber: 'INV-2026-044', clientName: 'CloudBase Ltd',    accountName: 'CloudBase Azure East',     amount: 24100, provider: 'Azure', status: 'OVERDUE' },
    { id: 'inv-005', invoiceNumber: 'INV-2026-045', clientName: 'Sigma Tech',       accountName: 'Sigma Tech AWS Staging',   amount: 18600, provider: 'AWS',   status: 'DRAFT' },
  ],

  recentTickets: [
    { id: 'tkt-001', subject: 'Cost spike on EC2 instances', category: 'Billing', severity: 'HIGH',   status: 'OPEN' },
    { id: 'tkt-002', subject: 'Invoice dispute for March',   category: 'Billing', severity: 'MEDIUM', status: 'IN_PROGRESS' },
    { id: 'tkt-003', subject: 'Credit note request',        category: 'Credits', severity: 'LOW',    status: 'OPEN' },
    { id: 'tkt-004', subject: 'New account onboarding',     category: 'General', severity: 'LOW',    status: 'IN_PROGRESS' },
  ],

  lastUpdated: new Date().toISOString(),
};

// ─── /api/billops/dashboard/admin/top-spending-refresh ───────────────────────
export const demoBillopsTopSpending: TopSpendingAccount[] = demoBillopsAdminDashboard.topSpendingAccounts;

// ─── /api/billops/admin/clients  (client list) ───────────────────────────────
export const demoBillopsClients = [
  { id: 1, name: 'Apex Corp',       email: 'billing@apexcorp.example', accountsCount: 4, totalSpend: 52400,  status: 'ACTIVE' },
  { id: 2, name: 'NexGen Solutions', email: 'billing@nexgen.example',   accountsCount: 3, totalSpend: 38900,  status: 'ACTIVE' },
  { id: 3, name: 'DataVault Inc',    email: 'billing@datavault.example', accountsCount: 2, totalSpend: 29700,  status: 'ACTIVE' },
  { id: 4, name: 'CloudBase Ltd',    email: 'billing@cloudbase.example', accountsCount: 3, totalSpend: 24100,  status: 'ACTIVE' },
  { id: 5, name: 'Sigma Tech',       email: 'billing@sigmatech.example', accountsCount: 2, totalSpend: 18600,  status: 'ACTIVE' },
  { id: 6, name: 'Orbit Systems',    email: 'billing@orbitsys.example',  accountsCount: 2, totalSpend: 14200,  status: 'PENDING' },
  { id: 7, name: 'Lumina Cloud',     email: 'billing@lumina.example',    accountsCount: 2, totalSpend: 10850,  status: 'ACTIVE' },
];

// ─── /api/billops/admin/invoices ─────────────────────────────────────────────
export const demoBillopsAdminInvoices = {
  content: [
    ...demoBillopsAdminDashboard.recentInvoices,
    { id: 'inv-006', invoiceNumber: 'INV-2026-031', clientName: 'Orbit Systems', accountName: 'Orbit Prod AWS',         amount: 14200, provider: 'AWS',   status: 'PAID' },
    { id: 'inv-007', invoiceNumber: 'INV-2026-032', clientName: 'Lumina Cloud',  accountName: 'Lumina GCP Primary',     amount: 10850, provider: 'GCP',   status: 'FINALIZED' },
    { id: 'inv-008', invoiceNumber: 'INV-2026-021', clientName: 'Apex Corp',     accountName: 'Apex Corp Staging',      amount: 8400,  provider: 'AWS',   status: 'PAID' },
  ],
  totalElements: 8,
  totalPages: 1,
  number: 0,
  size: 20,
};

// ─── /api/billops/admin/credits ──────────────────────────────────────────────
export const demoBillopsAdminCredits = {
  content: [
    { id: 'crd-001', clientName: 'DataVault Inc',    amount: 2400, reason: 'Service disruption compensation', status: 'APPLIED',  date: '2026-03-15' },
    { id: 'crd-002', clientName: 'CloudBase Ltd',    amount: 1200, reason: 'Early payment discount',          status: 'PENDING',  date: '2026-04-01' },
    { id: 'crd-003', clientName: 'Sigma Tech',       amount: 800,  reason: 'Promotional credit',              status: 'APPLIED',  date: '2026-02-20' },
    { id: 'crd-004', clientName: 'Lumina Cloud',     amount: 500,  reason: 'Referral bonus',                  status: 'APPROVED', date: '2026-04-10' },
  ],
  totalElements: 4,
  totalPages: 1,
  number: 0,
  size: 20,
};

// ─── /api/billops/admin/tickets ──────────────────────────────────────────────
export const demoBillopsAdminTickets = {
  content: [
    ...demoBillopsAdminDashboard.recentTickets,
    { id: 'tkt-005', subject: 'Reserved instance query',      category: 'Billing', severity: 'LOW',      status: 'CLOSED' },
    { id: 'tkt-006', subject: 'Azure subscription transfer',  category: 'General', severity: 'HIGH',     status: 'OPEN' },
    { id: 'tkt-007', subject: 'GCP committed use discount',   category: 'Billing', severity: 'MEDIUM',   status: 'IN_PROGRESS' },
  ],
  totalElements: 7,
  totalPages: 1,
  number: 0,
  size: 20,
};

// ─── /api/billops/admin/agreements ───────────────────────────────────────────
export const demoBillopsAdminAgreements = {
  content: [
    { id: 'agr-001', clientName: 'Apex Corp',        type: 'Enterprise',  startDate: '2025-01-01', endDate: '2025-12-31', status: 'ACTIVE',  discount: 8  },
    { id: 'agr-002', clientName: 'NexGen Solutions',  type: 'Standard',   startDate: '2025-04-01', endDate: '2026-03-31', status: 'ACTIVE',  discount: 5  },
    { id: 'agr-003', clientName: 'DataVault Inc',     type: 'Enterprise', startDate: '2025-07-01', endDate: '2026-06-30', status: 'ACTIVE',  discount: 10 },
    { id: 'agr-004', clientName: 'CloudBase Ltd',     type: 'Standard',   startDate: '2024-10-01', endDate: '2025-09-30', status: 'EXPIRED', discount: 5  },
    { id: 'agr-005', clientName: 'Sigma Tech',        type: 'Standard',   startDate: '2026-01-01', endDate: '2026-12-31', status: 'ACTIVE',  discount: 4  },
  ],
  totalElements: 5,
  totalPages: 1,
  number: 0,
  size: 20,
};

// ─── /api/billops/clients/spend  (clients spend summary used in admin dashboard) ─
export const demoBillopsClientsSpend = {
  total: 284750.40,
  byProvider: { AWS: 181200, GCP: 62300, Azure: 41250 },
  clients: demoBillopsClients,
};

// ─── Generic empty fallback ───────────────────────────────────────────────────
export const demoGenericEmpty = {};
