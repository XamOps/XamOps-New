/**
 * TypeScript types for BillOps Tickets functionality
 *
 * Converted from: billops/tickets.html
 */

export type TicketStatus = 'OPEN' | 'CLOSED' | 'IN_PROGRESS';
export type TicketSeverity = 'Low' | 'Medium' | 'Normal' | 'High' | 'Critical' | 'Urgent' | 'Info';
export type TicketCategory = 'Account and Billing' | 'Technical';

export interface BillOpsTicket {
  id: number;
  subject: string;
  description: string;
  status: TicketStatus;
  severity: TicketSeverity;
  category: TicketCategory;
  service: string;
  region?: string;
  accountId?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface CreateTicketFormData {
  category: TicketCategory;
  service: string;
  severity: TicketSeverity | '';
  subject: string;
  description: string;
  accountId?: string;
  region?: string;
}

export interface TicketCacheData {
  data: BillOpsTicket[];
  timestamp: number;
}

export const SERVICE_OPTIONS: Record<TicketCategory, string[]> = {
  'Account and Billing': [
    'Billing Inquiry',
    'Account Access',
    'Payment Issue',
    'Cost Explorer',
    'Invoice Dispute'
  ]
};

/**
 * Ticket reply interface for detail page
 */
export interface TicketReply {
  id: number;
  message: string;
  authorId: number;
  authorUsername: string;
  admin: boolean;
  createdAt: string;
  attachmentUrl?: string;
  attachmentName?: string;
  attachmentType?: string;
}

/**
 * Extended ticket with replies for detail view
 */
export interface BillOpsTicketDetail extends BillOpsTicket {
  replies: TicketReply[];
}

/**
 * Current user profile
 */
export interface BillOpsUserProfile {
  id: number;
  username: string;
  roles: string[];
}

/**
 * RCA form section
 */
export interface RCAFormSection {
  heading: string;
  content: string;
}

/**
 * RCA form data
 */
export interface RCAFormData {
  problemStatement: string;
  conclusion: string;
  incidentOverview: RCAFormSection[];
  descriptionSections: RCAFormSection[];
  rootCauseSections: RCAFormSection[];
  resolutionSections: RCAFormSection[];
  preventionSections: RCAFormSection[];
}
