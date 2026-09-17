// frontend-app/src/hooks/useSreInvestigation.ts
import apiClient from '../utils/apiClient';

export type InvestigationStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
export type ResourceType = 'K8S_POD' | 'EC2' | 'RDS' | 'AZURE_VM' | 'GCP_VM' | 'GENERIC';
export type TriggerType = 'AUTO' | 'MANUAL';
export type Verdict = 'RESOLVED' | 'FALSE_POSITIVE' | 'WRONG_ROOT_CAUSE';

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
  verdict?: Verdict;
  verdictAt?: string;
  verdictNote?: string;
  createdAt: string;
  startedAt?: string;
  completedAt?: string;
}

export interface SreStats {
  totalInvestigations: number;
  activeInvestigations: number;
  completedToday: number;
  failedToday: number;
  avgMttrSeconds: number | null;
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

// ─── Shared investigation list ──────────────────────────────────────────────
//
// SreInvestigateButton needs the investigation list to decide what to render, and it is used one
// per row in tables. On a GCP project with 2,124 container image vulnerability alerts that meant
// 2,124 identical GETs of the same endpoint on every page load, each returning the tenant's whole
// investigation list. Browsers allow around six concurrent connections per host, so they queued and
// the page appeared frozen — which is what "the Refresh button does not work" actually looked like
// from a customer's side.
//
// Concurrent callers share one in-flight request, and the result is briefly cached so a re-render
// does not start another round.

const INVESTIGATIONS_TTL_MS = 30_000;

let investigationsCache: { at: number; data: SreInvestigation[] } | null = null;
let investigationsInFlight: Promise<SreInvestigation[]> | null = null;

/**
 * The investigation list, coalescing concurrent callers into a single request.
 *
 * Prefer this over {@link listInvestigations} anywhere the caller might be rendered more than once
 * on a page.
 */
export const listInvestigationsShared = async (): Promise<SreInvestigation[]> => {
  if (investigationsCache && Date.now() - investigationsCache.at < INVESTIGATIONS_TTL_MS) {
    return investigationsCache.data;
  }
  if (investigationsInFlight) {
    return investigationsInFlight;
  }
  investigationsInFlight = listInvestigations()
    .then(data => {
      investigationsCache = { at: Date.now(), data };
      return data;
    })
    .finally(() => {
      investigationsInFlight = null;
    });
  return investigationsInFlight;
};

/**
 * Drop the cached list. Call after anything that changes it, so the next read is authoritative
 * rather than up to {@link INVESTIGATIONS_TTL_MS} stale.
 */
export const invalidateInvestigationsCache = (): void => {
  investigationsCache = null;
};

/** The most recent investigation for a resource, or null. */
export const findLatestInvestigation = (
  investigations: SreInvestigation[],
  resourceId: string,
  resourceType: ResourceType
): SreInvestigation | null => {
  const matches = investigations
    .filter(i => i.resourceId === resourceId && i.resourceType === resourceType)
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  return matches[0] ?? null;
};

export const getInvestigation = async (id: number): Promise<SreInvestigation> => {
  return apiClient.get<SreInvestigation>(`/api/sre/investigations/${id}`);
};

export const pollStatus = async (id: number): Promise<{ status: InvestigationStatus }> => {
  return apiClient.get<{ status: InvestigationStatus }>(`/api/sre/investigations/${id}/status`);
};

export const submitVerdict = async (id: number, verdict: Verdict, note?: string): Promise<SreInvestigation> => {
  return apiClient.put<SreInvestigation>(`/api/sre/investigations/${id}/verdict`, { verdict, note: note ?? '' });
};

export const getSreStats = async (): Promise<SreStats> => {
  return apiClient.get<SreStats>('/api/sre/stats');
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

export interface DetectionEvent {
  id: number;
  entity: string;
  signal: string;
  score: number;
  metricValue?: number;
  metricBaseline?: number;
  investigationId?: number;
  detectedAt: string;
}

export const listDetectionEvents = async (): Promise<DetectionEvent[]> => {
  return apiClient.get<DetectionEvent[]>('/api/sre/detection/events');
};

export const verifyDetectionStream = async (
  stream: string,
  entity: string
): Promise<{ connected: boolean; lastEventAt: string | null }> => {
  const params = new URLSearchParams({ stream, entity }).toString();
  return apiClient.get(`/api/sre/detection/verify?${params}`);
};
