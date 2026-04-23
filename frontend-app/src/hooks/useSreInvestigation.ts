import apiClient from '../utils/apiClient';

export type InvestigationStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
export type ResourceType = 'K8S_POD' | 'EC2' | 'RDS' | 'AZURE_VM' | 'GCP_VM';
export type TriggerType = 'AUTO' | 'MANUAL';

export interface SreInvestigation {
  id: number;
  clientId: number;
  cloudAccountId?: number;
  triggerType: TriggerType;
  resourceType: ResourceType;
  resourceId: string;
  resourceName?: string;
  status: InvestigationStatus;
  reportSummary?: string;
  reportRootCause?: string;
  reportRecommendations?: string[];
  reportFullMarkdown?: string;
  errorMessage?: string;
  createdAt: string;
  startedAt?: string;
  completedAt?: string;
}

export interface TriggerInvestigationRequest {
  cloudAccountId?: number;
  resourceType: ResourceType;
  resourceId: string;
  resourceName?: string;
  alertPayload?: string;
}

export interface SreNotification {
  id: number;
  investigationId: number;
  title: string;
  message?: string;
  read: boolean;
  createdAt: string;
}

export const triggerInvestigation = async (req: TriggerInvestigationRequest): Promise<SreInvestigation> => {
  return apiClient.post<SreInvestigation>('/api/sre/investigations', req);
};

export const listInvestigations = async (): Promise<SreInvestigation[]> => {
  return apiClient.get<SreInvestigation[]>('/api/sre/investigations');
};

export const getInvestigation = async (id: number): Promise<SreInvestigation> => {
  return apiClient.get<SreInvestigation>(`/api/sre/investigations/${id}`);
};

export const pollStatus = async (id: number): Promise<{ status: InvestigationStatus }> => {
  return apiClient.get<{ status: InvestigationStatus }>(`/api/sre/investigations/${id}/status`);
};

export const getNotifications = async (): Promise<SreNotification[]> => {
  return apiClient.get<SreNotification[]>('/api/sre/notifications');
};

export const getUnreadCount = async (): Promise<number> => {
  const data = await apiClient.get<{ count: number }>('/api/sre/notifications/count');
  return data.count;
};

export const markRead = async (id: number): Promise<void> => {
  await apiClient.put<void>(`/api/sre/notifications/${id}/read`);
};
