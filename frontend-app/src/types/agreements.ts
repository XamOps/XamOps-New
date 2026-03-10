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

/**
 * Admin Agreements Hook Result
 * Extended interface for admin operations (upload, finalize, delete)
 */
export interface UseAdminAgreementsResult extends UseAgreementsResult {
  // File upload state
  selectedFile: File | null;
  uploading: boolean;
  uploadError: string | null;
  uploadSuccess: boolean;

  // Confirm-action state
  pendingFinalize: Agreement | null;
  pendingDelete: Agreement | null;
  requestFinalize: (agreement: Agreement) => void;
  cancelFinalize: () => void;
  requestDelete: (agreement: Agreement) => void;
  cancelDelete: () => void;

  // Admin operations
  handleFileSelect: (event: React.ChangeEvent<HTMLInputElement>) => void;
  uploadAgreement: () => Promise<void>;
  finalizeAgreement: (agreement: Agreement) => Promise<void>;
  deleteAgreement: (agreement: Agreement) => Promise<void>;
  clearUploadState: () => void;
}

