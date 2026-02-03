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
}

export interface UseCreditsReturn {
  creditRequests: CreditRequest[];
  filteredRequests: CreditRequest[];
  loading: boolean;
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
}
