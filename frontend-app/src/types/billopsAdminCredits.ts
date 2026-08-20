/**
 * TypeScript types for BillOps Admin Credits Management
 *
 * Converted from: billops/admin_credits.html
 *
 * Defines all data structures for admin credit request management
 * For: ROLE_BILLOPS_ADMIN
 */

import type { CreditAttachment } from './credits';

export type { CreditAttachment };

export type AdminCreditStatus =
  | 'submitted'
  | 'documentation_review'
  | 'technical_verification'
  | 'approved'
  | 'rejected';

export interface AdminCreditRequest {
  id: number;
  awsAccountId: string;
  /** Resolved from cloud_account; absent when the submitted id is not linked to an account */
  accountName?: string | null;
  /** AWS / GCP / AZURE, resolved from the account or inferred from the submitted id */
  provider?: string | null;
  expectedCredits: number;
  services: string;
  useCase: string;
  /** Absent on requests submitted before the priority was stored server-side */
  priority?: 'low' | 'medium' | 'high' | null;
  userId: number;
  submittedDate: string;
  status: AdminCreditStatus;
  /** Supporting documents submitted with the request; links are minted per click */
  attachments?: CreditAttachment[];
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
