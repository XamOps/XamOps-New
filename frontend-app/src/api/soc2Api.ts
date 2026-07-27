import apiClient from '../utils/apiClient';

/**
 * Typed client for the SOC2 Program endpoints (xamops-service /api/soc2/*),
 * which proxy a self-hosted comp.ai instance. See CompAiController.
 */

export interface Soc2Overview {
  frameworkName: string;
  readinessPercent: number;
  controlsTotal: number;
  controlsPassing: number;
  controlsInProgress: number;
  controlsNotStarted: number;
  policiesTotal: number;
  policiesPublished: number;
  tasksTotal: number;
  tasksOpen: number;
  peopleTotal: number;
  vendorsTotal: number;
  risksTotal: number;
  cloudFindingsTotal: number;
  integrationsConnected: number;
  trustCenterUrl: string;
}

export type Soc2ControlStatus = 'PASSING' | 'IN_PROGRESS' | 'NOT_STARTED';

export interface Soc2ControlRequirementRef {
  identifier: string | null;
  name: string | null;
}

export interface Soc2ControlPolicyRef {
  id: string;
  name: string | null;
  status: string | null;
}

export interface Soc2Task {
  id: string;
  title: string;
  controlName: string | null;
  status: string | null;
  assigneeId: string | null;
  assigneeName: string | null;
}

export interface Soc2Control {
  id: string;
  name: string;
  description: string | null;
  category: string;
  requirementName: string | null;
  status: Soc2ControlStatus;
  policyName: string | null;
  policyStatus: string | null;
  taskCount: number;
  requirements: Soc2ControlRequirementRef[];
  policies: Soc2ControlPolicyRef[];
  tasks: Soc2Task[];
}

export interface Soc2Policy {
  id: string;
  name: string;
  description: string | null;
  status: string | null;
  content: string | null;
}

export interface Soc2Person {
  id: string;
  name: string;
  email: string;
  role: string;
  department: string | null;
  jobTitle: string | null;
  active: boolean;
}

export interface Soc2Vendor {
  id: string;
  name: string;
  category: string | null;
  status: string | null;
  description: string | null;
}

export interface Soc2Risk {
  id: string;
  title: string | null;
  description: string | null;
  category: string | null;
  status: string | null;
  severity: string | null;
  owner: string | null;
}

export interface Soc2CloudTest {
  id: string;
  title: string | null;
  severity: string | null;
  status: string | null;
  provider: string | null;
  resource: string | null;
}

export interface Soc2Integration {
  id: string;
  name: string;
  category: string | null;
  description: string | null;
  connected: boolean;
  capabilities: string | null;
}

export const fetchSoc2Overview = (): Promise<Soc2Overview> => apiClient.get('/api/soc2/overview');
export const fetchSoc2Controls = (): Promise<Soc2Control[]> => apiClient.get('/api/soc2/controls');
export const fetchSoc2Policies = (): Promise<Soc2Policy[]> => apiClient.get('/api/soc2/policies');
export const fetchSoc2Tasks = (): Promise<Soc2Task[]> => apiClient.get('/api/soc2/tasks');
export const fetchSoc2Personnel = (): Promise<Soc2Person[]> => apiClient.get('/api/soc2/personnel');
export const fetchSoc2Vendors = (): Promise<Soc2Vendor[]> => apiClient.get('/api/soc2/vendors');
export const fetchSoc2Risks = (): Promise<Soc2Risk[]> => apiClient.get('/api/soc2/risks');
export const fetchSoc2CloudTests = (): Promise<Soc2CloudTest[]> => apiClient.get('/api/soc2/cloud-tests');
export const fetchSoc2Integrations = (): Promise<Soc2Integration[]> => apiClient.get('/api/soc2/integrations');

// --- Write actions ---

export interface CreateVendorRequest {
  name: string;
  description: string;
  category: string;
  status: string;
}

export interface UpdateVendorRequest {
  name?: string;
  description?: string;
  category?: string;
  status?: string;
}

export interface CreateRiskRequest {
  title: string;
  description: string;
  category: string;
  department?: string;
}

export interface UpdateRiskRequest {
  title?: string;
  description?: string;
  category?: string;
  department?: string;
}

export interface UpdateTaskRequest {
  title?: string;
  description?: string;
  assigneeId?: string;
  status?: string;
}

export interface CreateTaskRequest {
  title: string;
  description: string;
  assigneeId?: string;
  department?: string;
  controlIds?: string[];
}

export interface UpdatePersonRequest {
  role?: string;
  department?: string;
  isActive?: boolean;
}

export interface InviteMemberRequest {
  email: string;
  role: string;
}

const patch = <T>(url: string, body: unknown): Promise<T> =>
  apiClient.fetchJson(url, { method: 'PATCH', body: JSON.stringify(body) });

export const publishSoc2Policy = (policyId: string): Promise<Soc2Policy> =>
  apiClient.post(`/api/soc2/policies/${policyId}/publish`);

export const setSoc2PolicyArchived = (policyId: string, archived: boolean): Promise<Soc2Policy> =>
  patch(`/api/soc2/policies/${policyId}/archive`, { archived });

export const updateSoc2Task = (taskId: string, request: UpdateTaskRequest): Promise<Soc2Task> =>
  patch(`/api/soc2/tasks/${taskId}`, request);

export const createSoc2Task = (request: CreateTaskRequest): Promise<Soc2Task> =>
  apiClient.post('/api/soc2/tasks', request);

export const createSoc2Vendor = (request: CreateVendorRequest): Promise<Soc2Vendor> =>
  apiClient.post('/api/soc2/vendors', request);

export const updateSoc2Vendor = (vendorId: string, request: UpdateVendorRequest): Promise<Soc2Vendor> =>
  patch(`/api/soc2/vendors/${vendorId}`, request);

export const deleteSoc2Vendor = (vendorId: string): Promise<void> =>
  apiClient.delete(`/api/soc2/vendors/${vendorId}`);

export const createSoc2Risk = (request: CreateRiskRequest): Promise<Soc2Risk> =>
  apiClient.post('/api/soc2/risks', request);

export const updateSoc2Risk = (riskId: string, request: UpdateRiskRequest): Promise<Soc2Risk> =>
  patch(`/api/soc2/risks/${riskId}`, request);

export const deleteSoc2Risk = (riskId: string): Promise<void> =>
  apiClient.delete(`/api/soc2/risks/${riskId}`);

export const deleteSoc2Control = (controlId: string): Promise<void> =>
  apiClient.delete(`/api/soc2/controls/${controlId}`);

export const inviteSoc2Member = (request: InviteMemberRequest): Promise<void> =>
  apiClient.post('/api/soc2/personnel/invite', request);

export const updateSoc2Person = (memberId: string, request: UpdatePersonRequest): Promise<Soc2Person> =>
  patch(`/api/soc2/personnel/${memberId}`, request);

export const deleteSoc2Person = (memberId: string): Promise<void> =>
  apiClient.delete(`/api/soc2/personnel/${memberId}`);
