import apiClient from '../utils/apiClient';
import type {
  ResourceLabel,
  LabeledResource,
  LabelAssignRequest,
  ResourceMapRequest,
  ResourceLabelMap,
  CreateLabelRequest,
  PagedResponse,
} from '../types/resourceLabels';

const BASE = '/api/xamops/labels';

// ── Label CRUD ──────────────────────────────────────────────────────────────

export const fetchLabels = (): Promise<ResourceLabel[]> =>
  apiClient.get<ResourceLabel[]>(BASE);

export const createLabel = (body: CreateLabelRequest): Promise<ResourceLabel> =>
  apiClient.post<ResourceLabel>(BASE, body);

export const updateLabel = (labelId: number, body: Partial<CreateLabelRequest>): Promise<ResourceLabel> =>
  apiClient.put<ResourceLabel>(`${BASE}/${labelId}`, body);

export const deleteLabel = (labelId: number): Promise<void> =>
  apiClient.delete<void>(`${BASE}/${labelId}`);

// ── Assign / remove ─────────────────────────────────────────────────────────

export const assignLabel = (req: LabelAssignRequest): Promise<{ assigned: number }> =>
  apiClient.post<{ assigned: number }>(`${BASE}/assign`, req);

export const removeLabel = (req: LabelAssignRequest): Promise<void> =>
  apiClient.post<void>(`${BASE}/remove`, req);

// ── Cloud List enrichment ────────────────────────────────────────────────────

/**
 * One round-trip to get all label assignments for a page of resources.
 * Call once after the resource list loads to populate the Labels column.
 */
export const fetchResourceLabelMap = (req: ResourceMapRequest): Promise<ResourceLabelMap> =>
  apiClient.post<ResourceLabelMap>(`${BASE}/resource-map`, req);

// ── View by label ────────────────────────────────────────────────────────────

export const fetchResourcesByLabel = (
  labelId: number,
  page = 0,
  size = 50
): Promise<PagedResponse<LabeledResource>> =>
  apiClient.get<PagedResponse<LabeledResource>>(
    `${BASE}/${labelId}/resources?page=${page}&size=${size}`
  );

// ── Connected Resources ───────────────────────────────────────────────────────

export interface ConnectedResource {
  id: string;
  name: string | null;
  type: string;
  region: string;
  details: Record<string, string>;
  labels: ResourceLabel[];
}

export const fetchConnectedResources = (
  accountId: string,
  resourceId: string,
  resourceType: string,
  region: string
): Promise<ConnectedResource[]> =>
  apiClient.get<ConnectedResource[]>(
    `${BASE}/connected-resources?accountId=${encodeURIComponent(accountId)}&resourceId=${encodeURIComponent(resourceId)}&resourceType=${encodeURIComponent(resourceType)}&region=${encodeURIComponent(region)}`
  );

// ── Per-label cost ────────────────────────────────────────────────────────────

export interface TrackedResource {
  id: string;
  name: string;
  type: string;
  region: string;
}

export interface LabelCostResponse {
  costs: Array<{ name: string; amount: number }>;
  resourceLevel: boolean;         // true = filtered by specific resource IDs; false = service-level approximation
  message?: string;
  approximationWarning?: string;  // present when some costs are full account-level (not per-resource)
  trackedResources?: TrackedResource[];
}

export const fetchLabelCost = (
  labelId: number,
  accountId: string,
  startDate: string,
  endDate: string
): Promise<LabelCostResponse> =>
  apiClient.get<LabelCostResponse>(
    `${BASE}/${labelId}/cost?accountId=${encodeURIComponent(accountId)}&startDate=${startDate}&endDate=${endDate}`
  );
