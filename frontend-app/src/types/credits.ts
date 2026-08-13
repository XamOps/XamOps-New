/**
 * TypeScript types for Credits Management
 *
 * Converted from: billops/credits.html
 *
 * Defines all data structures for credit requests and management
 */

export type CreditStatus = 'submitted' | 'documentation_review' | 'technical_verification' | 'approved' | 'rejected';
export type CreditPriority = 'low' | 'medium' | 'high';
export type CreditFilter = 'all' | CreditStatus;

/**
 * Supporting document attached to a credit request.
 *
 * Carries no URL on purpose — links are minted per click from
 * /api/billops/credits/{id}/attachments/{index}/url so they never expire inside a cache.
 */
export interface CreditAttachment {
  index: number;
  name: string;
  type: string;
  size?: number | null;
}

/**
 * Credit Request Data Structure
 *
 * Note: awsAccountId field supports multicloud identifiers:
 * - AWS Account IDs (12-digit number)
 * - GCP Project IDs (alphanumeric string)
 * - Azure Subscription IDs (GUID format)
 */
export interface CreditRequest {
  id: number;
  awsAccountId: string; // Multicloud: supports AWS/GCP/Azure identifiers
  expectedCredits: number;
  services: string; // Multicloud: can include services from any cloud provider
  useCase: string;
  priority: CreditPriority;
  userId: number;
  submittedDate: string;
  status: CreditStatus;
  attachments?: CreditAttachment[];
}

/**
 * New Credit Request Form Data
 * 
 * Maintains awsAccountId field name for backward compatibility with backend API
 */
export interface NewCreditRequest {
  awsAccountId: string; // Multicloud: supports AWS/GCP/Azure identifiers
  expectedCredits: number | null;
  services: string; // Multicloud: can include services from any cloud provider
  useCase: string;
  priority: CreditPriority;
  files: File[]; // Supporting documents uploaded with the request
}

export interface UseCreditsReturn {
  creditRequests: CreditRequest[];
  filteredRequests: CreditRequest[];
  loading: boolean;
  /** True while a request is being submitted — uploads of large documents can run for a while */
  isSubmitting: boolean;
  error: string | null;
  selectedFilter: CreditFilter;
  showForm: boolean;
  newRequestData: NewCreditRequest;

  // Statistics
  totalRequested: number;
  pendingCount: number;

  // Actions
  setShowForm: (show: boolean) => void;
  setSelectedFilter: (filter: CreditFilter) => void;
  setNewRequestData: (data: NewCreditRequest) => void;
  submitRequest: () => Promise<void>;
  resetForm: () => void;
  refreshData: () => void;
  addFiles: (files: File[]) => void;
  removeFile: (index: number) => void;
}
