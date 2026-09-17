/**
 * TypeScript types for BillOps Admin Tickets functionality
 *
 * Converted from: billops/admin_tickets.html
 * For ROLE_BILLOPS_ADMIN users
 */

export type AdminTicketStatus = 'OPEN' | 'CLOSED' | 'IN_PROGRESS';
export type AdminTicketSeverity = 'Low' | 'Medium' | 'Normal' | 'High' | 'Critical' | 'Urgent' | 'Info';
export type AdminTicketCategory = 'Account and Billing' | 'Technical';
export type AdminTicketTab = 'Account and Billing' | 'Technical' | 'All';

export interface AdminTicket {
  id: number;
  subject: string;
  description: string;
  status: AdminTicketStatus;
  severity: AdminTicketSeverity;
  category: AdminTicketCategory;
  service: string;
  region?: string;
  accountId?: string;
  accountName?: string;
  clientId?: number;
  creatorId?: number;
  creatorUsername?: string;
  createdAt: string;
  updatedAt?: string;
  closedAt?: string;
  chatStartedAt?: string;
  assignedToId?: number;
  assignedToUsername?: string;
  hasUnreadUserReply?: boolean;  // true = customer replied and admin hasn't responded yet
  // Stop-the-clock resolution SLA (backend Phase 1). slaActiveMs = banked awaiting-admin time;
  // slaRunningSince = ISO timestamp while the clock is running, null/absent while paused.
  slaActiveMs?: number;
  slaRunningSince?: string | null;
}

export interface AdminTicketCacheData {
  data: AdminTicket[];
  timestamp: number;
}

/**
 * Tab configuration for the admin tickets page
 */
export interface TabConfig {
  id: AdminTicketTab;
  label: string;
  icon: string;
}

export const ADMIN_TICKET_TABS: TabConfig[] = [
  { id: 'All', label: 'All Tickets', icon: 'fas fa-list' },
  { id: 'Account and Billing', label: 'Account & Billing', icon: 'fas fa-file-invoice-dollar' },
  { id: 'Technical', label: 'Technical Support', icon: 'fas fa-server' }
];
