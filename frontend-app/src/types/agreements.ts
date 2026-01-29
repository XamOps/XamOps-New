/**
 * Agreements Types
 *
 * TypeScript interfaces for Agreements page
 * API Endpoint: /api/billops/agreements/account/{accountId}
 */

export interface Agreement {
  id: number;
  fileName: string;
  status: 'DRAFT' | 'FINALIZED';
  uploadedAt: string; // ISO date string
  finalizedAt: string | null; // ISO date string
  downloadUrl: string;
  uploadedBy: string | null;
  cloudAccountId: number;
}

export interface UseAgreementsResult {
  agreements: Agreement[];
  loading: boolean;
  error: string | null;
  accountId: string | null;
  accountName: string;
  fetchAgreements: () => Promise<void>;
}
