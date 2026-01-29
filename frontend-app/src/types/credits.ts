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

export interface CreditRequest {
  id: number;
  awsAccountId: string;
  expectedCredits: number;
  services: string;
  useCase: string;
  priority: CreditPriority;
  userId: number;
  submittedDate: string;
  status: CreditStatus;
}

export interface NewCreditRequest {
  awsAccountId: string;
  expectedCredits: number | null;
  services: string;
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
