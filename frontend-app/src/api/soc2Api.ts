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

export interface Soc2Control {
  id: string;
  name: string;
  category: string;
  requirementName: string | null;
  status: Soc2ControlStatus;
  policyName: string | null;
  policyStatus: string | null;
  taskCount: number;
}

export interface Soc2Policy {
  id: string;
  name: string;
  description: string | null;
  status: string | null;
}

export interface Soc2Task {
  id: string;
  title: string;
  controlName: string | null;
  status: string | null;
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

export interface CreateRiskRequest {
  title: string;
  description: string;
  category: string;
  department?: string;
}

export const publishSoc2Policy = (policyId: string): Promise<Soc2Policy> =>
  apiClient.post(`/api/soc2/policies/${policyId}/publish`);

export const updateSoc2TaskStatus = (taskId: string, status: string): Promise<Soc2Task> =>
  apiClient.fetchJson(`/api/soc2/tasks/${taskId}`, {
    method: 'PATCH',
    body: JSON.stringify({ status }),
  });

export const createSoc2Vendor = (request: CreateVendorRequest): Promise<Soc2Vendor> =>
  apiClient.post('/api/soc2/vendors', request);

export const createSoc2Risk = (request: CreateRiskRequest): Promise<Soc2Risk> =>
  apiClient.post('/api/soc2/risks', request);
