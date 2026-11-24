import apiClient, { apiFetch } from '../utils/apiClient';
import type {
  ResourceLabel,
  ResourceRef,
  LabeledResource,
  LabelAssignRequest,
  ResourceMapRequest,
  ResourceLabelMap,
  CreateLabelRequest,
  PagedResponse,
  ResourceLabelSplitRequest,
  ServiceLabelSplitRequest,
  ServiceLabelMap,
  LabelChangeSource,
  LabelHistoryPage,
} from '../types/resourceLabels';

const BASE = '/api/xamops/labels';

// ── Label CRUD ──────────────────────────────────────────────────────────────

export const fetchLabels = (): Promise<ResourceLabel[]> =>
  apiClient.get<ResourceLabel[]>(BASE);

export const createLabel = (body: CreateLabelRequest): Promise<ResourceLabel> =>
  apiClient.post<ResourceLabel>(BASE, body);

export const updateLabel = (labelId: number, body: Partial<CreateLabelRequest>): Promise<ResourceLabel> =>
  apiClient.put<ResourceLabel>(`${BASE}/${labelId}`, body);

export const deleteLabel = (labelId: number, source?: LabelChangeSource): Promise<void> =>
  apiClient.delete<void>(`${BASE}/${labelId}${source ? `?source=${encodeURIComponent(source)}` : ''}`);

// ── Assign / remove ─────────────────────────────────────────────────────────

export const assignLabel = (req: LabelAssignRequest): Promise<{ assigned: number }> =>
  apiClient.post<{ assigned: number }>(`${BASE}/assign`, req);

/**
 * Assign one label to many resources in a single call.
 *
 * <p>A thin alias over {@link assignLabel}: the `/assign` endpoint has always taken a list of
 * resources and returned how many were assigned, so bulk labelling is one round-trip, not one
 * per resource. Kept as its own name so a caller's intent reads as "label this whole selection"
 * rather than "assign, with a list that happens to be long".
 */
export const bulkAssignLabel = (
  accountId: string,
  labelId: number,
  resources: ResourceRef[],
  source?: LabelChangeSource,
): Promise<{ assigned: number }> =>
  assignLabel({ accountId, labelId, resources, source });

export const removeLabel = (req: LabelAssignRequest): Promise<void> =>
  apiClient.post<void>(`${BASE}/remove`, req);

// ── Label shares ────────────────────────────────────────────────────────────

/**
 * Sets every label on one resource, each with its share. Labels left out are removed.
 * Shares may add up to less than 100 (the rest is unallocated) but never more.
 * Returns the resource's labels as stored, each carrying its percent.
 */
export const setResourceLabelShares = (req: ResourceLabelSplitRequest): Promise<ResourceLabel[]> =>
  apiClient.put<ResourceLabel[]>(`${BASE}/resource-split`, req);

/** Every service label on one account: service name → labels with their shares. */
export const fetchServiceLabelMap = (accountId: string): Promise<ServiceLabelMap> =>
  apiClient.get<ServiceLabelMap>(`${BASE}/service-map?accountId=${encodeURIComponent(accountId)}`);

/** As {@link setResourceLabelShares}, for a whole billing service. */
export const setServiceLabelShares = (req: ServiceLabelSplitRequest): Promise<ResourceLabel[]> =>
  apiClient.put<ResourceLabel[]>(`${BASE}/service-split`, req);

// ── Audit trail ──────────────────────────────────────────────────────────────

/**
 * Who changed which labels and shares, newest first.
 *
 * Without a target: everything on the account, plus the client's label edits. With one: a single
 * resource (any id form) or service. Page back with `beforeId`, the oldest event id already shown.
 */
export const fetchLabelHistory = (q: {
  accountId: string;
  targetType?: 'RESOURCE' | 'SERVICE';
  targetId?: string;
  beforeId?: number;
  size?: number;
}): Promise<LabelHistoryPage> => {
  const params = new URLSearchParams({ accountId: q.accountId });
  if (q.targetType && q.targetId) {
    params.set('targetType', q.targetType);
    params.set('targetId', q.targetId);
  }
  if (q.beforeId != null) params.set('beforeId', String(q.beforeId));
  if (q.size != null) params.set('size', String(q.size));
  return apiClient.get<LabelHistoryPage>(`${BASE}/history?${params.toString()}`);
};

// ── Label spend ──────────────────────────────────────────────────────────────

/** One resource's part of a label's spend, or a service's charges with no resource. */
export interface LabelSpendItem {
  /** Empty for charges with no resource behind them. */
  resourceId: string;
  name: string | null;
  service: string;
  region: string;
  /** The resource's whole cost under this service this month. */
  sourceCost: number;
  /** What this label takes of it. */
  amount: number;
  /** amount ÷ sourceCost as a percent, or null when the cost nets to zero. */
  percent: number | null;
  /** Which label put it here: "resource", "service" or "resource+service". */
  via: string;
}

export interface LabelSpendTotal {
  labelId: number;
  name: string;
  color: string;
  total: number;
  /** Percent of the bill. */
  share: number;
  resourceCount: number;
  /** Items before the list was capped. */
  itemCount: number;
  items: LabelSpendItem[];
}

export interface LabelSpend {
  status: 'OK' | 'NO_REPORT';
  message: string | null;
  period: string;
  basis: string;
  billTotal: number;
  allocatedTotal: number;
  unallocatedTotal: number;
  /** Every label with an assignment on this account, costliest first. */
  labels: LabelSpendTotal[];
}

/**
 * What every label cost in one month, after resource labels, service labels and their shares.
 * Resource labels take their shares first; service labels share what is left of each line.
 */
export const fetchLabelSpend = (
  accountId: string,
  period: string,
  basis: string,
): Promise<LabelSpend> =>
  apiClient.get<LabelSpend>(
    `/api/xamops/label-spend/${encodeURIComponent(accountId)}?period=${encodeURIComponent(period)}&basis=${encodeURIComponent(basis)}`,
  );

/**
 * Downloads one month's label spend as a PDF — every label, or one — and saves it.
 *
 * <p>Through {@link apiFetch} rather than a plain link, so the session, CSRF and tenant headers
 * go with it. Accepts JSON too, so a refusal arrives as a sentence rather than a broken file.
 */
export const downloadLabelSpendPdf = async (q: {
  accountId: string;
  period: string;
  basis: string;
  /** One label, or null for all labels. */
  labelId: number | null;
  currency?: string;
}): Promise<void> => {
  const params = new URLSearchParams({ period: q.period, basis: q.basis });
  if (q.labelId != null) params.set('labelId', String(q.labelId));
  if (q.currency) params.set('currency', q.currency);
  const res = await apiFetch(
    `/api/xamops/label-spend/${encodeURIComponent(q.accountId)}/export.pdf?${params.toString()}`,
    // The first read of a month is an Athena query, which can take a while.
    { headers: { Accept: 'application/pdf, application/json' }, timeoutMs: 120_000 },
  );
  if (!res.ok) {
    let message = `Could not create the PDF (HTTP ${res.status}).`;
    try {
      const body = await res.json();
      if (body?.error || body?.message) message = String(body.error ?? body.message);
    } catch {
      // Not JSON; keep the status sentence.
    }
    throw new Error(message);
  }
  const blob = await res.blob();
  const name =
    /filename="?([^";]+)"?/i.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ??
    `label-spend-${q.period}.pdf`;
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  // After the click has handed the file to the browser.
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};

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
