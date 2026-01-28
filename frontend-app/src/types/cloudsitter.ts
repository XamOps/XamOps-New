/**
 * CloudSitter TypeScript Type Definitions
 *
 * Type definitions for CloudSitter EC2 scheduling feature
 */

export interface EC2Resource {
  id: string;
  name: string;
  region: string;
  type: string;
  state: string;
  policyName?: string | null;
  savings: number;
}

export interface CloudSitterPolicy {
  id: string;
  name: string;
  type: string;
  timeZone: string;
  notificationsEnabled: boolean;
  scheduleJson?: string;
}

export interface PolicyAssignment {
  policy: CloudSitterPolicy;
  monthlySavings: number;
}

export interface PolicyFormData {
  id: string | null;
  name: string;
  type: string;
  timezone: string;
  notificationsEnabled: boolean;
}

export interface PolicyGrid {
  [day: number]: boolean[];
}

export type ViewType = 'dashboard' | 'createPolicy';

export interface CloudSitterState {
  currentView: ViewType;
  isLoading: boolean;
  isSaving: boolean;
  searchQuery: string;
  accountId: string | null;
  resources: EC2Resource[];
  policies: CloudSitterPolicy[];
  assignments: Record<string, PolicyAssignment>;

  // Policy Form & State
  policyForm: PolicyFormData;
  policyGrid: boolean[][];
  editMode: boolean;
  isDragging: boolean;
  dragState: boolean;

  // Modals
  showAssignModal: boolean;
  showManagePoliciesModal: boolean;
  assignTarget: EC2Resource | null;
  selectedPolicyId: string;
  isCreatingFromAssign: boolean;
}
