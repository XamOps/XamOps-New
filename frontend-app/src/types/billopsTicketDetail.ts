/**
 * TypeScript types for BillOps Ticket Detail functionality
 *
 * Converted from: billops/ticket_detail.html
 * For ROLE_BILLOPS_ADMIN and ROLE_BILLOPS users
 */

export type TicketStatus = 'OPEN' | 'CLOSED' | 'IN_PROGRESS';
export type TicketSeverity = 'Low' | 'Medium' | 'Normal' | 'High' | 'Critical' | 'Urgent' | 'Info';
export type TicketCategory = 'Account and Billing' | 'Technical';

export interface TicketReply {
  id: number;
  message: string;
  authorId: number;
  authorUsername: string;
  admin: boolean;
  attachmentUrl?: string;
  attachmentName?: string;
  attachmentType?: string;
  createdAt: string;
  edited?: boolean;
  editedAt?: string;
  deleted?: boolean;
}

export interface Ticket {
  id: number;
  subject: string;
  description: string;
  status: TicketStatus;
  severity: TicketSeverity;
  category: TicketCategory;
  service: string;
  region?: string;
  accountId?: string;
  creatorId?: number;
  createdAt: string;
  updatedAt?: string;
  closedAt?: string;
  chatStartedAt?: string;
  // Stop-the-clock resolution SLA (backend Phase 1).
  slaActiveMs?: number;
  slaRunningSince?: string | null;
  replies: TicketReply[];
}

export interface CurrentUser {
  id: number | null;
  username: string;
  roles: string[];
}

export interface RcaFormSection {
  heading: string;
  content: string;
}

export interface RcaFormData {
  problemStatement: string;
  conclusion: string;
  incidentOverview: RcaFormSection[];
  descriptionSections: RcaFormSection[];
  rootCauseSections: RcaFormSection[];
  resolutionSections: RcaFormSection[];
  preventionSections: RcaFormSection[];
}

export interface UseBillOpsTicketDetailReturn {
  loading: boolean;
  isSubmitting: boolean;
  ticket: Ticket | null;
  newReply: string;
  newReplyHasContent: boolean;
  attachment: File | null;
  fileName: string;
  currentUser: CurrentUser;
  showRcaModal: boolean;
  rcaSubmitting: boolean;
  rcaFormData: RcaFormData;
  isAdmin: boolean;

  setNewReply: (reply: string) => void;
  setNewReplyHasContent: (hasContent: boolean) => void;
  setShowRcaModal: (show: boolean) => void;
  handleFileSelect: (file: File | null) => void;
  clearFile: () => void;
  submitReply: (htmlContent: string) => Promise<void>;
  closeTicket: () => Promise<void>;
  addRcaSection: (arrayName: keyof Pick<RcaFormData, 'incidentOverview' | 'descriptionSections' | 'rootCauseSections' | 'resolutionSections' | 'preventionSections'>) => void;
  removeRcaSection: (arrayName: keyof Pick<RcaFormData, 'incidentOverview' | 'descriptionSections' | 'rootCauseSections' | 'resolutionSections' | 'preventionSections'>, index: number) => void;
  updateRcaFormData: (updates: Partial<RcaFormData>) => void;
  updateRcaSection: (arrayName: keyof Pick<RcaFormData, 'incidentOverview' | 'descriptionSections' | 'rootCauseSections' | 'resolutionSections' | 'preventionSections'>, index: number, field: 'heading' | 'content', value: string) => void;
  submitRca: () => Promise<void>;
  isMe: (reply: TicketReply) => boolean;
  formatDate: (dateString: string) => string;
  formatDateSimple: (dateString: string) => string;
  getInitials: (name: string) => string;
  goBackToTickets: () => void;
}
