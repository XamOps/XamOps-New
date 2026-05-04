/**
 * BillOps Admin Demo Mock Data  (ROLE_BILLOPS_ADMIN)
 *
 * Realistic-looking but fully static data returned for every API call
 * when the demo user with ROLE_BILLOPS_ADMIN is logged in.
 */

import type { BillOpsAdminDashboardData, TopSpendingAccount } from '../types/billopsAdminDashboard';
import type { AdminCreditRequest } from '../types/billopsAdminCredits';

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
    { id: 'inv-001', invoiceNumber: 'INV-2026-041', clientName: 'Apex Corp',        accountName: 'Apex Corp Production',   amount: 52400, provider: 'AWS',   status: 'FINALIZED' },
    { id: 'inv-002', invoiceNumber: 'INV-2026-042', clientName: 'NexGen Solutions', accountName: 'NexGen Cloud Main',       amount: 38900, provider: 'AWS',   status: 'DRAFT' },
    { id: 'inv-003', invoiceNumber: 'INV-2026-043', clientName: 'DataVault Inc',    accountName: 'DataVault GCP',           amount: 29700, provider: 'GCP',   status: 'PAID' },
    { id: 'inv-004', invoiceNumber: 'INV-2026-044', clientName: 'CloudBase Ltd',    accountName: 'CloudBase Azure East',    amount: 24100, provider: 'Azure', status: 'OVERDUE' },
    { id: 'inv-005', invoiceNumber: 'INV-2026-045', clientName: 'Sigma Tech',       accountName: 'Sigma Tech AWS Staging',  amount: 18600, provider: 'AWS',   status: 'DRAFT' },
  ],

  recentTickets: [
    { id: 'tkt-001', subject: 'Cost spike on EC2 instances', category: 'Billing', severity: 'HIGH',   status: 'OPEN' },
    { id: 'tkt-002', subject: 'Invoice dispute for March',   category: 'Billing', severity: 'MEDIUM', status: 'IN_PROGRESS' },
    { id: 'tkt-003', subject: 'Credit note request',         category: 'Credits', severity: 'LOW',    status: 'OPEN' },
    { id: 'tkt-004', subject: 'New account onboarding',      category: 'General', severity: 'LOW',    status: 'IN_PROGRESS' },
  ],

  lastUpdated: new Date().toISOString(),
};

// ─── /api/billops/dashboard/admin/top-spending-refresh ───────────────────────
export const demoBillopsTopSpending: TopSpendingAccount[] = demoBillopsAdminDashboard.topSpendingAccounts;

// ─── /api/billops/admin/clients  (client list) ───────────────────────────────
export const demoBillopsClients = [
  { id: 1, name: 'Apex Corp',        email: 'billing@apexcorp.example',  accountsCount: 4, totalSpend: 52400, status: 'ACTIVE'  },
  { id: 2, name: 'NexGen Solutions', email: 'billing@nexgen.example',    accountsCount: 3, totalSpend: 38900, status: 'ACTIVE'  },
  { id: 3, name: 'DataVault Inc',    email: 'billing@datavault.example', accountsCount: 2, totalSpend: 29700, status: 'ACTIVE'  },
  { id: 4, name: 'CloudBase Ltd',    email: 'billing@cloudbase.example', accountsCount: 3, totalSpend: 24100, status: 'ACTIVE'  },
  { id: 5, name: 'Sigma Tech',       email: 'billing@sigmatech.example', accountsCount: 2, totalSpend: 18600, status: 'ACTIVE'  },
  { id: 6, name: 'Orbit Systems',    email: 'billing@orbitsys.example',  accountsCount: 2, totalSpend: 14200, status: 'PENDING' },
  { id: 7, name: 'Lumina Cloud',     email: 'billing@lumina.example',    accountsCount: 2, totalSpend: 10850, status: 'ACTIVE'  },
];

// ─── /api/billops/admin/invoices ─────────────────────────────────────────────
export const demoBillopsAdminInvoices = {
  content: [
    ...demoBillopsAdminDashboard.recentInvoices,
    { id: 'inv-006', invoiceNumber: 'INV-2026-031', clientName: 'Orbit Systems', accountName: 'Orbit Prod AWS',     amount: 14200, provider: 'AWS', status: 'PAID'      },
    { id: 'inv-007', invoiceNumber: 'INV-2026-032', clientName: 'Lumina Cloud',  accountName: 'Lumina GCP Primary', amount: 10850, provider: 'GCP', status: 'FINALIZED' },
    { id: 'inv-008', invoiceNumber: 'INV-2026-021', clientName: 'Apex Corp',     accountName: 'Apex Corp Staging',  amount: 8400,  provider: 'AWS', status: 'PAID'      },
  ],
  totalElements: 8,
  totalPages: 1,
  number: 0,
  size: 20,
};

// ─── /api/admin/invoices  (flat array used by useBillOpsAdminInvoices hook) ──
export const demoAdminInvoicesList = [
  { id: 1, invoiceNumber: 'INV-2026-041', accountName: 'Apex Corp Production',   awsAccountId: '123456789012',     gcpProjectId: '', azureSubscriptionId: '', cloudAccountId: '123456789012',     billingPeriod: '2026-04', amount: 52400, status: 'FINALIZED' },
  { id: 2, invoiceNumber: 'INV-2026-042', accountName: 'NexGen Cloud Main',       awsAccountId: '234567890123',     gcpProjectId: '', azureSubscriptionId: '', cloudAccountId: '234567890123',     billingPeriod: '2026-04', amount: 38900, status: 'DRAFT'     },
  { id: 3, invoiceNumber: 'INV-2026-043', accountName: 'DataVault GCP',           awsAccountId: '',                 gcpProjectId: 'proj-datavault-prod', azureSubscriptionId: '', cloudAccountId: 'proj-datavault-prod', billingPeriod: '2026-04', amount: 29700, status: 'FINALIZED' },
  { id: 4, invoiceNumber: 'INV-2026-044', accountName: 'CloudBase Azure East',    awsAccountId: '',                 gcpProjectId: '', azureSubscriptionId: 'sub-cloudbase-001', cloudAccountId: 'sub-cloudbase-001', billingPeriod: '2026-04', amount: 24100, status: 'DRAFT'     },
  { id: 5, invoiceNumber: 'INV-2026-045', accountName: 'Sigma Tech AWS Staging',  awsAccountId: '345678901234',     gcpProjectId: '', azureSubscriptionId: '', cloudAccountId: '345678901234',     billingPeriod: '2026-04', amount: 18600, status: 'DRAFT'     },
  { id: 6, invoiceNumber: 'INV-2026-031', accountName: 'Orbit Prod AWS',          awsAccountId: '456789012345',     gcpProjectId: '', azureSubscriptionId: '', cloudAccountId: '456789012345',     billingPeriod: '2026-03', amount: 14200, status: 'FINALIZED' },
  { id: 7, invoiceNumber: 'INV-2026-032', accountName: 'Lumina GCP Primary',      awsAccountId: '',                 gcpProjectId: 'proj-lumina-prod', azureSubscriptionId: '', cloudAccountId: 'proj-lumina-prod', billingPeriod: '2026-03', amount: 10850, status: 'FINALIZED' },
  { id: 8, invoiceNumber: 'INV-2026-021', accountName: 'Apex Corp Staging',       awsAccountId: '123456789012',     gcpProjectId: '', azureSubscriptionId: '', cloudAccountId: '123456789012',     billingPeriod: '2026-02', amount: 8400,  status: 'FINALIZED' },
];

// ─── /api/billops/admin/credits ──────────────────────────────────────────────
export const demoBillopsAdminCredits = {
  content: [
    { id: 'crd-001', clientName: 'DataVault Inc', amount: 2400, reason: 'Service disruption compensation', status: 'APPLIED',  date: '2026-03-15' },
    { id: 'crd-002', clientName: 'CloudBase Ltd', amount: 1200, reason: 'Early payment discount',          status: 'PENDING',  date: '2026-04-01' },
    { id: 'crd-003', clientName: 'Sigma Tech',    amount: 800,  reason: 'Promotional credit',              status: 'APPLIED',  date: '2026-02-20' },
    { id: 'crd-004', clientName: 'Lumina Cloud',  amount: 500,  reason: 'Referral bonus',                  status: 'APPROVED', date: '2026-04-10' },
  ],
  totalElements: 4,
  totalPages: 1,
  number: 0,
  size: 20,
};

// ─── /api/billops/credits/admin/all  (flat array for useBillOpsAdminCredits) ─
export const demoAdminCreditRequests: AdminCreditRequest[] = [
  { id: 1, awsAccountId: '123456789012',     expectedCredits: 2400, services: 'EC2, RDS',       useCase: 'Service disruption compensation during maintenance window',    priority: 'high',   userId: 10, submittedDate: '2026-03-15', status: 'approved' },
  { id: 2, awsAccountId: '234567890123',     expectedCredits: 1200, services: 'CloudFront, S3', useCase: 'Early payment discount for Q1 commitment',                   priority: 'medium', userId: 11, submittedDate: '2026-04-01', status: 'documentation_review' },
  { id: 3, awsAccountId: '345678901234',     expectedCredits: 800,  services: 'Lambda, S3',     useCase: 'Promotional credit for new workload migration',               priority: 'low',    userId: 12, submittedDate: '2026-02-20', status: 'approved' },
  { id: 4, awsAccountId: 'proj-lumina-prod', expectedCredits: 500,  services: 'GCS, BigQuery',  useCase: 'Referral bonus for client onboarding',                       priority: 'low',    userId: 17, submittedDate: '2026-04-10', status: 'technical_verification' },
  { id: 5, awsAccountId: 'sub-cloudbase-001',expectedCredits: 950,  services: 'Azure VMs, AKS', useCase: 'Cost optimization trial for Azure workload consolidation',    priority: 'medium', userId: 14, submittedDate: '2026-04-20', status: 'submitted' },
  { id: 6, awsAccountId: '456789012345',     expectedCredits: 600,  services: 'EC2, EBS',       useCase: 'Credit for unplanned over-provisioning in March',            priority: 'medium', userId: 16, submittedDate: '2026-03-28', status: 'documentation_review' },
];

// ─── /api/billops/admin/tickets ──────────────────────────────────────────────
export const demoBillopsAdminTickets = {
  content: [
    ...demoBillopsAdminDashboard.recentTickets,
    { id: 'tkt-005', subject: 'Reserved instance query',      category: 'Billing', severity: 'LOW',    status: 'CLOSED'      },
    { id: 'tkt-006', subject: 'Azure subscription transfer',  category: 'General', severity: 'HIGH',   status: 'OPEN'        },
    { id: 'tkt-007', subject: 'GCP committed use discount',   category: 'Billing', severity: 'MEDIUM', status: 'IN_PROGRESS' },
  ],
  totalElements: 7,
  totalPages: 1,
  number: 0,
  size: 20,
};

// ─── /api/billops/tickets  (used by useBillOpsAdminTickets hook — flat array) ─
export const demoBillopsAdminTicketsList = [
  { id: 3001, subject: 'Cost spike on EC2 instances',    description: 'April EC2 costs are 20% higher than March. Need detailed breakdown and investigation.', status: 'OPEN',        severity: 'High',   category: 'Account and Billing', service: 'Billing Inquiry',    accountId: '123456789012', accountName: 'Apex Corp Production',   creatorId: 10, creatorUsername: 'john.apex',      createdAt: '2026-05-01T10:00:00Z', updatedAt: '2026-05-04T09:00:00Z', hasUnreadUserReply: true  },
  { id: 3002, subject: 'Invoice dispute for March',      description: 'March invoice shows unexpected RDS charges. We believe there is a 20% overcharge.',     status: 'IN_PROGRESS', severity: 'Medium', category: 'Account and Billing', service: 'Invoice Dispute',    accountId: '234567890123', accountName: 'NexGen Cloud Main',       creatorId: 11, creatorUsername: 'sarah.nexgen',   createdAt: '2026-04-28T14:30:00Z', updatedAt: '2026-05-03T16:00:00Z', hasUnreadUserReply: false },
  { id: 3003, subject: 'Credit note request',            description: 'Requesting credit for service downtime on 15 March 2026 affecting production workload.',  status: 'OPEN',        severity: 'Low',    category: 'Account and Billing', service: 'Payment Issue',      accountId: 'proj-datavault-prod', accountName: 'DataVault GCP',      creatorId: 13, creatorUsername: 'priya.datavault',createdAt: '2026-04-20T11:00:00Z', updatedAt: '2026-04-25T10:00:00Z', hasUnreadUserReply: true  },
  { id: 3004, subject: 'New account onboarding',         description: 'We need to add two new AWS accounts to our XamOps subscription.',                         status: 'IN_PROGRESS', severity: 'Low',    category: 'Account and Billing', service: 'Account Access',     accountId: 'sub-cloudbase-001',  accountName: 'CloudBase Azure East',creatorId: 14, creatorUsername: 'alex.cloudbase', createdAt: '2026-04-22T09:00:00Z', updatedAt: '2026-05-02T15:00:00Z', hasUnreadUserReply: false },
  { id: 3005, subject: 'Reserved instance query',        description: 'Query about RI utilization and whether we qualify for additional reserved capacity.',      status: 'CLOSED',      severity: 'Low',    category: 'Account and Billing', service: 'Cost Explorer',      accountId: '345678901234',       accountName: 'Sigma Tech AWS Staging',creatorId: 12, creatorUsername: 'mike.sigma',     createdAt: '2026-04-10T08:00:00Z', updatedAt: '2026-04-18T12:00:00Z', closedAt: '2026-04-18T12:00:00Z', hasUnreadUserReply: false },
  { id: 3006, subject: 'Azure subscription transfer',    description: 'We are consolidating two Azure subscriptions and need guidance on the transfer process.',   status: 'OPEN',        severity: 'High',   category: 'Technical',           service: 'Infrastructure Issue',accountId: 'sub-cloudbase-001',  accountName: 'CloudBase Azure East',creatorId: 14, creatorUsername: 'alex.cloudbase', createdAt: '2026-04-30T13:00:00Z', updatedAt: '2026-05-03T10:00:00Z', hasUnreadUserReply: false },
  { id: 3007, subject: 'GCP committed use discount',     description: 'We want to understand the committed use discount options for our BigQuery workloads.',     status: 'IN_PROGRESS', severity: 'Medium', category: 'Account and Billing', service: 'Cost Explorer',      accountId: 'proj-datavault-prod', accountName: 'DataVault GCP',      creatorId: 13, creatorUsername: 'priya.datavault',createdAt: '2026-04-25T15:00:00Z', updatedAt: '2026-05-01T11:00:00Z', hasUnreadUserReply: false },
  { id: 3008, subject: 'Performance degradation on RDS', description: 'Our RDS postgres instance is showing slow query times since the April 28 maintenance.',    status: 'OPEN',        severity: 'Critical',category: 'Technical',           service: 'Infrastructure Issue',accountId: '123456789012',       accountName: 'Apex Corp Production',  creatorId: 10, creatorUsername: 'john.apex',      createdAt: '2026-05-03T07:30:00Z', updatedAt: '2026-05-04T08:00:00Z', hasUnreadUserReply: true  },
  { id: 3009, subject: 'Tagging policy enforcement help',description: 'Need help setting up automated tagging policies for new resources in our AWS accounts.',   status: 'OPEN',        severity: 'Low',    category: 'Technical',           service: 'Integration Help',   accountId: '456789012345',       accountName: 'Orbit Prod AWS',        creatorId: 16, creatorUsername: 'anna.orbit',     createdAt: '2026-05-04T09:00:00Z', updatedAt: '2026-05-04T09:00:00Z', hasUnreadUserReply: false },
];

// ─── /api/billops/admin/agreements (paginated, for dashboard lists) ──────────
export const demoBillopsAdminAgreements = {
  content: [
    { id: 'agr-001', clientName: 'Apex Corp',        type: 'Enterprise', startDate: '2025-01-01', endDate: '2025-12-31', status: 'ACTIVE',  discount: 8  },
    { id: 'agr-002', clientName: 'NexGen Solutions', type: 'Standard',   startDate: '2025-04-01', endDate: '2026-03-31', status: 'ACTIVE',  discount: 5  },
    { id: 'agr-003', clientName: 'DataVault Inc',    type: 'Enterprise', startDate: '2025-07-01', endDate: '2026-06-30', status: 'ACTIVE',  discount: 10 },
    { id: 'agr-004', clientName: 'CloudBase Ltd',    type: 'Standard',   startDate: '2024-10-01', endDate: '2025-09-30', status: 'EXPIRED', discount: 5  },
    { id: 'agr-005', clientName: 'Sigma Tech',       type: 'Standard',   startDate: '2026-01-01', endDate: '2026-12-31', status: 'ACTIVE',  discount: 4  },
  ],
  totalElements: 5,
  totalPages: 1,
  number: 0,
  size: 20,
};

// ─── /api/billops/agreements/account/{accountId}  (flat array, useAgreements) ─
// Fields must match the Agreement interface in src/types/agreements.ts
export const demoBillopsAgreementsByAccount = [
  { id: 1, fileName: 'MSA-Apex-Corp-2025.pdf',          status: 'FINALIZED', uploadedAt: '2025-01-02T10:00:00Z', finalizedAt: '2025-01-05T12:00:00Z', downloadUrl: '#', uploadedBy: 'support.admin', cloudAccountId: 1 },
  { id: 2, fileName: 'NexGen-Service-Agreement-Q2.pdf',  status: 'FINALIZED', uploadedAt: '2025-04-02T09:00:00Z', finalizedAt: '2025-04-05T11:00:00Z', downloadUrl: '#', uploadedBy: 'support.admin', cloudAccountId: 2 },
  { id: 3, fileName: 'DataVault-Enterprise-MSA.pdf',     status: 'DRAFT',     uploadedAt: '2025-07-01T14:00:00Z', finalizedAt: null,                   downloadUrl: '#', uploadedBy: 'support.admin', cloudAccountId: 3 },
];

// ─── /api/billops/clients/spend ──────────────────────────────────────────────
export const demoBillopsClientsSpend = {
  total: 284750.40,
  byProvider: { AWS: 181200, GCP: 62300, Azure: 41250 },
  clients: demoBillopsClients,
};

// ─── /api/billops/support/accounts ───────────────────────────────────────────
// Fields must match what useBillOpsAdminInvoices maps: { id, accountName, provider, accountId }
export const demoSupportAccounts = [
  { id: 1, accountId: '123456789012',     accountName: 'Apex Corp Production',   provider: 'AWS'   },
  { id: 2, accountId: '234567890123',     accountName: 'NexGen Cloud Main',       provider: 'AWS'   },
  { id: 3, accountId: 'proj-datavault-prod', accountName: 'DataVault GCP',         provider: 'GCP'   },
  { id: 4, accountId: 'sub-cloudbase-001', accountName: 'CloudBase Azure East',   provider: 'Azure' },
  { id: 5, accountId: '345678901234',     accountName: 'Sigma Tech AWS Staging',  provider: 'AWS'   },
  { id: 6, accountId: '456789012345',     accountName: 'Orbit Prod AWS',           provider: 'AWS'   },
  { id: 7, accountId: 'proj-lumina-prod', accountName: 'Lumina GCP Primary',      provider: 'GCP'   },
  { id: 8, accountId: '567890123456',     accountName: 'Apex Corp Staging',        provider: 'AWS'   },
];

// ─── /api/billops/support/clients ────────────────────────────────────────────
export const demoSupportClients = [
  { id: 1, name: 'Apex Corp'        },
  { id: 2, name: 'NexGen Solutions' },
  { id: 3, name: 'DataVault Inc'    },
  { id: 4, name: 'CloudBase Ltd'    },
  { id: 5, name: 'Sigma Tech'       },
  { id: 6, name: 'Orbit Systems'    },
  { id: 7, name: 'Lumina Cloud'     },
];

// ─── /api/billops/support/clients/{id}/profiles ──────────────────────────────
// Returned for any clientId — good enough for demo
export const demoSupportProfiles = [
  { id: 10, username: 'john.apex',        email: 'john@apexcorp.example',    contact: '+1-555-0101', accounts: [{ id: 1, accountId: '123456789012',     accountName: 'Apex Corp Production',  provider: 'AWS'   }, { id: 8, accountId: '567890123456', accountName: 'Apex Corp Staging',      provider: 'AWS' }] },
  { id: 15, username: 'lisa.apex',        email: 'lisa@apexcorp.example',    contact: '+1-555-0102', accounts: [{ id: 8, accountId: '567890123456',     accountName: 'Apex Corp Staging',      provider: 'AWS'   }] },
  { id: 11, username: 'sarah.nexgen',     email: 'sarah@nexgen.example',     contact: '+1-555-0103', accounts: [{ id: 2, accountId: '234567890123',     accountName: 'NexGen Cloud Main',      provider: 'AWS'   }] },
  { id: 13, username: 'priya.datavault',  email: 'priya@datavault.example',  contact: '+91-98765-43210', accounts: [{ id: 3, accountId: 'proj-datavault-prod', accountName: 'DataVault GCP', provider: 'GCP' }] },
  { id: 14, username: 'alex.cloudbase',   email: 'alex@cloudbase.example',   contact: '+44-20-5555-0105', accounts: [{ id: 4, accountId: 'sub-cloudbase-001', accountName: 'CloudBase Azure East', provider: 'Azure' }] },
  { id: 12, username: 'mike.sigma',       email: 'mike@sigmatech.example',   contact: '+1-555-0106', accounts: [{ id: 5, accountId: '345678901234',     accountName: 'Sigma Tech AWS Staging', provider: 'AWS'  }] },
  { id: 16, username: 'anna.orbit',       email: 'anna@orbitsys.example',    contact: '+1-555-0107', accounts: [{ id: 6, accountId: '456789012345',     accountName: 'Orbit Prod AWS',         provider: 'AWS'   }] },
  { id: 17, username: 'raj.lumina',       email: 'raj@lumina.example',       contact: '+91-99999-12345', accounts: [{ id: 7, accountId: 'proj-lumina-prod', accountName: 'Lumina GCP Primary',   provider: 'GCP' }] },
];

// ─── /api/billops/support/inbox ──────────────────────────────────────────────
export const demoSupportInbox = [
  { userId: 10, username: 'john.apex',       email: 'john@apexcorp.example',    accountId: '123456789012',     accountName: 'Apex Corp Production',   provider: 'AWS',   lastMessage: 'The EC2 costs for April seem much higher than expected. Can you help?',         lastMessageTime: '2026-05-04T12:30:00Z', unreadCount: 2, adminUnreadCount: 0, ticketStatus: 'OPEN',        assignedAdminUsername: 'support.admin' },
  { userId: 11, username: 'sarah.nexgen',    email: 'sarah@nexgen.example',     accountId: '234567890123',     accountName: 'NexGen Cloud Main',       provider: 'AWS',   lastMessage: 'We need to dispute the March invoice — there is a 20% overcharge on RDS.',     lastMessageTime: '2026-05-03T16:45:00Z', unreadCount: 1, adminUnreadCount: 0, ticketStatus: 'IN_PROGRESS', assignedAdminUsername: 'support.admin' },
  { userId: 12, username: 'mike.sigma',      email: 'mike@sigmatech.example',   accountId: '345678901234',     accountName: 'Sigma Tech AWS Staging',  provider: 'AWS',   lastMessage: 'Could you help with reserved instance discount queries?',                       lastMessageTime: '2026-05-02T09:20:00Z', unreadCount: 0, adminUnreadCount: 1, ticketStatus: 'OPEN',        assignedAdminUsername: null },
  { userId: 13, username: 'priya.datavault', email: 'priya@datavault.example',  accountId: 'proj-datavault-prod', accountName: 'DataVault GCP',        provider: 'GCP',   lastMessage: 'Thank you for approving the credit! When will it reflect in the dashboard?',   lastMessageTime: '2026-05-01T14:10:00Z', unreadCount: 0, adminUnreadCount: 0, ticketStatus: 'IN_PROGRESS', assignedAdminUsername: 'support.admin' },
  { userId: 14, username: 'alex.cloudbase',  email: 'alex@cloudbase.example',   accountId: 'sub-cloudbase-001', accountName: 'CloudBase Azure East',  provider: 'Azure', lastMessage: 'We have two Azure subs to consolidate — need a step-by-step transfer guide.',   lastMessageTime: '2026-04-30T11:15:00Z', unreadCount: 0, adminUnreadCount: 0, ticketStatus: 'OPEN',        assignedAdminUsername: null },
];

// ─── /api/billops/support/chat/user/{userId} ─────────────────────────────────
export const demoSupportChat = [
  { id: 1, accountId: '123456789012', content: 'Hi, I noticed our EC2 costs for April jumped significantly. Can you help explain the spike?',                                                                                                                                          timestamp: '2026-05-04T10:00:00Z', userMessage: true,  senderName: 'John (Apex Corp)',  senderUsername: 'john.apex',     status: 'READ'      },
  { id: 2, accountId: '123456789012', content: "Hi John, I'm looking into the April EC2 usage now. I can see a spike in your us-east-1 region starting April 15. Could you confirm if you launched any new workloads around that time?",                                              timestamp: '2026-05-04T10:15:00Z', userMessage: false, senderName: 'Support Admin',    senderUsername: 'support.admin', status: 'DELIVERED' },
  { id: 3, accountId: '123456789012', content: 'Yes, we scaled up our prod-api-asg for a large event we hosted. It should have reverted by April 20. Can you confirm if the scaling policy triggered correctly?',                                                                      timestamp: '2026-05-04T10:30:00Z', userMessage: true,  senderName: 'John (Apex Corp)',  senderUsername: 'john.apex',     status: 'READ'      },
  { id: 4, accountId: '123456789012', content: 'I can see the ASG scaled back on April 21 — one day delay. The EC2 costs are $52,400 for April vs $48,600 in March (7.8% increase), which aligns with the event traffic. I can send a detailed cost breakdown if that would help.', timestamp: '2026-05-04T11:00:00Z', userMessage: false, senderName: 'Support Admin',    senderUsername: 'support.admin', status: 'DELIVERED' },
  { id: 5, accountId: '123456789012', content: 'That would be great — please send the breakdown. Also, can we set up auto-scale cooldown policies to avoid this next time?',                                                                                                           timestamp: '2026-05-04T12:30:00Z', userMessage: true,  senderName: 'John (Apex Corp)',  senderUsername: 'john.apex',     status: 'UNREAD'    },
];

// ─── /api/billops/users  (admin user manager) ────────────────────────────────
export const demoBillopsUsers = [
  { id: 'user-001', clientName: 'Apex Corp',        username: 'john.apex',        email: 'john@apexcorp.example',     role: 'ROLE_BILLOPS', contact: '+1-555-0101', designation: 'CFO',                 parentId: null       },
  { id: 'user-002', clientName: 'Apex Corp',        username: 'lisa.apex',        email: 'lisa@apexcorp.example',     role: 'ROLE_BILLOPS', contact: '+1-555-0102', designation: 'DevOps Lead',          parentId: 'user-001' },
  { id: 'user-003', clientName: 'NexGen Solutions', username: 'sarah.nexgen',     email: 'sarah@nexgen.example',      role: 'ROLE_BILLOPS', contact: '+1-555-0103', designation: 'Finance Manager',      parentId: null       },
  { id: 'user-004', clientName: 'DataVault Inc',    username: 'priya.datavault',  email: 'priya@datavault.example',   role: 'ROLE_BILLOPS', contact: '+91-98765-43210', designation: 'Cloud Architect', parentId: null       },
  { id: 'user-005', clientName: 'CloudBase Ltd',    username: 'alex.cloudbase',   email: 'alex@cloudbase.example',    role: 'ROLE_BILLOPS', contact: '+44-20-5555-0105', designation: 'CTO',           parentId: null       },
  { id: 'user-006', clientName: 'Sigma Tech',       username: 'mike.sigma',       email: 'mike@sigmatech.example',    role: 'ROLE_BILLOPS', contact: '+1-555-0106', designation: 'Operations Manager',   parentId: null       },
  { id: 'user-007', clientName: 'Orbit Systems',    username: 'anna.orbit',       email: 'anna@orbitsys.example',     role: 'ROLE_BILLOPS', contact: '+1-555-0107', designation: 'Finance Lead',         parentId: null       },
  { id: 'user-008', clientName: 'Lumina Cloud',     username: 'raj.lumina',       email: 'raj@lumina.example',        role: 'ROLE_BILLOPS', contact: '+91-99999-12345', designation: 'Head of Engineering', parentId: null  },
];

// ─── /api/xamops/billops-admin/clients  (client details page) ────────────────
export const demoClientDetails = [
  {
    id: 1, name: 'Apex Corp', email: 'billing@apexcorp.example', totalConsumption: 60800,
    accounts: [
      { id: 1, name: 'Apex Corp Production', providerAccountId: '123456789012', provider: 'AWS',   consumption: 52400, creditsApplied: 0    },
      { id: 8, name: 'Apex Corp Staging',    providerAccountId: '567890123456', provider: 'AWS',   consumption: 8400,  creditsApplied: 0    },
    ],
    users: [
      { id: 10, username: 'john.apex',  email: 'john@apexcorp.example', accountAccessIds: [1, 8], parentId: null, lastLoginAt: '2026-05-04T08:30:00Z' },
      { id: 15, username: 'lisa.apex',  email: 'lisa@apexcorp.example', accountAccessIds: [8],    parentId: 10,   lastLoginAt: '2026-05-03T14:00:00Z' },
    ],
  },
  {
    id: 2, name: 'NexGen Solutions', email: 'billing@nexgen.example', totalConsumption: 38900,
    accounts: [
      { id: 2, name: 'NexGen Cloud Main', providerAccountId: '234567890123', provider: 'AWS', consumption: 38900, creditsApplied: 0 },
    ],
    users: [
      { id: 11, username: 'sarah.nexgen', email: 'sarah@nexgen.example', accountAccessIds: [2], parentId: null, lastLoginAt: '2026-05-03T16:45:00Z' },
    ],
  },
  {
    id: 3, name: 'DataVault Inc', email: 'billing@datavault.example', totalConsumption: 29700,
    accounts: [
      { id: 3, name: 'DataVault GCP', providerAccountId: 'proj-datavault-prod', provider: 'GCP', consumption: 29700, creditsApplied: 2400 },
    ],
    users: [
      { id: 13, username: 'priya.datavault', email: 'priya@datavault.example', accountAccessIds: [3], parentId: null, lastLoginAt: '2026-05-01T14:10:00Z' },
    ],
  },
  {
    id: 4, name: 'CloudBase Ltd', email: 'billing@cloudbase.example', totalConsumption: 24100,
    accounts: [
      { id: 4, name: 'CloudBase Azure East', providerAccountId: 'sub-cloudbase-001', provider: 'Azure', consumption: 24100, creditsApplied: 0 },
    ],
    users: [
      { id: 14, username: 'alex.cloudbase', email: 'alex@cloudbase.example', accountAccessIds: [4], parentId: null, lastLoginAt: '2026-04-30T11:00:00Z' },
    ],
  },
  {
    id: 5, name: 'Sigma Tech', email: 'billing@sigmatech.example', totalConsumption: 18600,
    accounts: [
      { id: 5, name: 'Sigma Tech AWS Staging', providerAccountId: '345678901234', provider: 'AWS', consumption: 18600, creditsApplied: 800 },
    ],
    users: [
      { id: 12, username: 'mike.sigma', email: 'mike@sigmatech.example', accountAccessIds: [5], parentId: null, lastLoginAt: '2026-05-02T09:20:00Z' },
    ],
  },
  {
    id: 6, name: 'Orbit Systems', email: 'billing@orbitsys.example', totalConsumption: 14200,
    accounts: [
      { id: 6, name: 'Orbit Prod AWS', providerAccountId: '456789012345', provider: 'AWS', consumption: 14200, creditsApplied: 0 },
    ],
    users: [
      { id: 16, username: 'anna.orbit', email: 'anna@orbitsys.example', accountAccessIds: [6], parentId: null, lastLoginAt: '2026-04-28T10:00:00Z' },
    ],
  },
  {
    id: 7, name: 'Lumina Cloud', email: 'billing@lumina.example', totalConsumption: 10850,
    accounts: [
      { id: 7, name: 'Lumina GCP Primary', providerAccountId: 'proj-lumina-prod', provider: 'GCP', consumption: 10850, creditsApplied: 500 },
    ],
    users: [
      { id: 17, username: 'raj.lumina', email: 'raj@lumina.example', accountAccessIds: [7], parentId: null, lastLoginAt: '2026-05-04T07:00:00Z' },
    ],
  },
];

// ─── /api/xamops/billops-admin/clients/{id}/savings ──────────────────────────
export const demoClientSavings = {
  totalSavings: 12400,
  savingsBreakdown: [
    { service: 'EC2 Rightsizing',      savings: 5200, recommendation: 'Downsize 3 oversized instances to match actual utilization' },
    { service: 'EBS Optimization',     savings: 2800, recommendation: 'Delete 5 unattached volumes and 12 stale snapshots'         },
    { service: 'Reserved Instances',   savings: 4400, recommendation: 'Purchase 3-year RIs for 4 steady-state workloads'           },
  ],
};

// ─── /api/admin/invoices/{id}  (single invoice detail for detail page) ───────
export const demoBillopsAdminInvoiceDetail = {
  id: 1,
  invoiceNumber: 'INV-2026-041',
  invoiceDate: '2026-04-30T00:00:00Z',
  billingPeriod: '2026-04',
  status: 'FINALIZED' as const,
  clientName: 'Apex Corp',
  accountName: 'Apex Corp Production',
  provider: 'AWS',
  awsAccountId: '123456789012',
  gcpProjectId: '',
  azureSubscriptionId: '',
  preDiscountTotal: 54800,
  discountAmount: 2400,
  taxAmount: 0,
  amount: 52400,
  lineItems: [
    { id: 1, serviceName: 'EC2', regionName: 'us-east-1', resourceName: 'prod-api-server-01', usageQuantity: '720', unit: 'Hrs', cost: 24500 },
    { id: 2, serviceName: 'EC2', regionName: 'us-east-1', resourceName: 'prod-api-server-02', usageQuantity: '720', unit: 'Hrs', cost: 18200 },
    { id: 3, serviceName: 'RDS', regionName: 'us-east-1', resourceName: 'prod-postgres-main', usageQuantity: '720', unit: 'Hrs', cost: 8600 },
    { id: 4, serviceName: 'S3',  regionName: 'us-east-1', resourceName: 'apex-prod-data',      usageQuantity: '2.4', unit: 'TB', cost: 3500 },
  ],
  discounts: [
    { id: 1, serviceName: 'EC2', percentage: 8, description: 'Enterprise discount' },
  ],
};

// ─── /api/billops/tickets/{id}  (single ticket detail with replies) ──────────
export const demoBillopsAdminTicketDetail = {
  id: 3001,
  subject: 'Cost spike on EC2 instances',
  description: 'April EC2 costs are 20% higher than March. Need detailed breakdown and investigation.',
  status: 'OPEN' as const,
  severity: 'High' as const,
  category: 'Account and Billing' as const,
  service: 'Billing Inquiry',
  accountId: '123456789012',
  createdAt: '2026-05-01T10:00:00Z',
  updatedAt: '2026-05-04T09:00:00Z',
  closedAt: null,
  chatStartedAt: null,
  assignedToId: null,
  assignedToUsername: null,
  creatorUsername: 'john.apex',
  creatorEmail: 'john@apexcorp.example',
  hasUnreadAdminReply: false,
  replies: [
    { id: 101, message: 'We noticed a 20% increase in our EC2 costs for April. Could you provide a detailed breakdown and investigate the cause?', authorId: 10, authorUsername: 'john.apex',    admin: false, createdAt: '2026-05-01T10:05:00Z' },
    { id: 102, message: "Hi John, I'm looking into the April EC2 usage. I can see a spike in us-east-1 starting April 15. Did you launch new workloads around that time?", authorId: 1, authorUsername: 'support.admin', admin: true,  createdAt: '2026-05-01T11:00:00Z' },
    { id: 103, message: 'Yes, we scaled up prod-api-asg for a large event. It should have reverted by April 20. Can you confirm the scaling policy triggered correctly?', authorId: 10, authorUsername: 'john.apex',    admin: false, createdAt: '2026-05-01T14:00:00Z' },
    { id: 104, message: 'I can see the ASG scaled back on April 21 — one day delay. EC2 costs are $52,400 for April vs $48,600 in March (7.8% increase), which aligns with the event traffic. Want a detailed breakdown?', authorId: 1, authorUsername: 'support.admin', admin: true, createdAt: '2026-05-04T09:00:00Z' },
  ],
};

// ─── /api/billops/clients/{id}/accounts  (raise ticket modal step 2) ─────────
export const demoBillopsClientAccounts = [
  { id: 1, accountName: 'Apex Corp Production',   provider: 'AWS',   accountIdentifier: '123456789012'        },
  { id: 8, accountName: 'Apex Corp Staging',       provider: 'AWS',   accountIdentifier: '567890123456'        },
  { id: 2, accountName: 'NexGen Cloud Main',        provider: 'AWS',   accountIdentifier: '234567890123'        },
  { id: 3, accountName: 'DataVault GCP',            provider: 'GCP',   accountIdentifier: 'proj-datavault-prod' },
  { id: 4, accountName: 'CloudBase Azure East',     provider: 'Azure', accountIdentifier: 'sub-cloudbase-001'  },
];

// ─── Generic empty fallback ───────────────────────────────────────────────────
export const demoGenericEmpty = {};
