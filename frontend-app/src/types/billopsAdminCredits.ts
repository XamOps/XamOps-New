/**
 * TypeScript types for BillOps Admin Credits Management
 *
 * Converted from: billops/admin_credits.html
 *
 * Defines all data structures for admin credit request management
 * For: ROLE_BILLOPS_ADMIN
 */

export type AdminCreditStatus =
  | 'submitted'
  | 'documentation_review'
  | 'technical_verification'
  | 'approved'
  | 'rejected';

export interface AdminCreditRequest {
  id: number;
  awsAccountId: string;
  expectedCredits: number;
  services: string;
  useCase: string;
  priority: 'low' | 'medium' | 'high';
  userId: number;
  submittedDate: string;
  status: AdminCreditStatus;
}

export interface UseBillOpsAdminCreditsReturn {
  requests: AdminCreditRequest[];
  loading: boolean;
  isUpdating: boolean;
  error: string | null;
  updateError: string | null;

  // Actions
  fetchData: (forceFresh?: boolean) => void;
  updateStatus: (id: number, newStatus: AdminCreditStatus) => Promise<void>;
}
