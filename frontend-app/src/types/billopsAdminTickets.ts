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
  creatorId?: number;
  createdAt: string;
  updatedAt?: string;
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
  { id: 'Account and Billing', label: 'Account & Billing', icon: 'fas fa-file-invoice-dollar' },
  { id: 'Technical', label: 'Technical Support', icon: 'fas fa-server' },
  { id: 'All', label: 'All Tickets', icon: 'fas fa-list' }
];
