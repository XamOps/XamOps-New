import { apiFetch } from '../utils/apiClient';

/**
 * AWS Cost Optimization Hub recommendations for the AWS Rightsizing page.
 *
 * The Hub is a cross-service, cross-region recommendation feed (rightsizing, idle-resource
 * deletion, Savings Plans / Reserved Instances, architecture migration) that is distinct from the
 * per-service AWS / XamAi recommendations the page already shows. The Hub can be disabled for an
 * account; the status endpoint reports enablement so the UI can show enable-guidance rather than a
 * bare "no recommendations" empty-state. Every backend endpoint answers 200 with a safe body on
 * error, so these helpers degrade to an empty list / null / {enabled:false} rather than throwing.
 */

// ── Response shape checking ──────────────────────────────────────────────────

const isRecord = (body: unknown): body is Record<string, unknown> =>
  typeof body === 'object' && body !== null && !Array.isArray(body);

/** Reads a JSON body, returning null when it is unreadable or not the declared shape. */
const readJson = async <T>(res: Response, isValid: (body: unknown) => boolean): Promise<T | null> => {
  let body: unknown;
  try {
    body = await res.json();
  } catch {
    return null;
  }
  if (!isValid(body)) {
    console.warn('[COH] Ignoring unexpected response shape from', res.url);
    return null;
  }
  return body as T;
};

// ── Types (mirror the backend DTOs) ──────────────────────────────────────────

/** One Cost Optimization Hub recommendation, as listed. Mirrors the backend list DTO. */
export interface CohRecommendation {
  recommendationId: string;
  accountId: string | null;
  /** e.g. Rightsize, Stop, Delete, Upgrade, MigrateToGraviton. */
  actionType: string | null;
  currentResourceType: string | null;
  currentResourceSummary: string | null;
  recommendedResourceType: string | null;
  recommendedResourceSummary: string | null;
  resourceArn: string | null;
  resourceId: string | null;
  region: string | null;
  estimatedMonthlySavings: number | null;
  estimatedSavingsPercentage: number | null;
  estimatedMonthlyCost: number | null;
  /** e.g. VeryLow, Low, Medium, High, VeryHigh. */
  implementationEffort: string | null;
  restartNeeded: boolean | null;
  rollbackPossible: boolean | null;
  currencyCode: string | null;
  lastRefreshTimestamp: string | null;
  tags: Record<string, string> | null;
}

/** Drill-down detail for one recommendation. Mirrors the backend detail DTO. */
export interface CohRecommendationDetail extends CohRecommendation {
  /** Flattened current-resource configuration + cost breakdown (before). Insertion-ordered. */
  currentResourceDetails: Record<string, string>;
  /** Flattened recommended-resource configuration + cost breakdown (after). Insertion-ordered. */
  recommendedResourceDetails: Record<string, string>;
  recommendationLookbackPeriodInDays: number | null;
  costCalculationLookbackPeriodInDays: number | null;
  estimatedSavingsOverCostCalculationLookbackPeriod: number | null;
}

export interface CohStatus {
  enabled: boolean;
}

// ── Endpoints ─────────────────────────────────────────────────────────────────

/** All Hub recommendations for the account, savings-descending. Empty list on any error. */
export const fetchCohRecommendations = async (
  accountId: string,
  forceRefresh = false,
): Promise<CohRecommendation[]> => {
  const params = new URLSearchParams({ accountId });
  if (forceRefresh) params.set('forceRefresh', 'true');
  const res = await apiFetch(`/api/xamops/cost-optimization-hub/recommendations?${params}`);
  if (!res.ok) return [];
  const body = await readJson<CohRecommendation[]>(res, Array.isArray);
  return body ?? [];
};

/** Drill-down detail for one recommendation. Null when unavailable. */
export const fetchCohRecommendationDetail = async (
  accountId: string,
  recommendationId: string,
): Promise<CohRecommendationDetail | null> => {
  const params = new URLSearchParams({ accountId });
  const res = await apiFetch(
    `/api/xamops/cost-optimization-hub/recommendations/${encodeURIComponent(recommendationId)}?${params}`,
  );
  if (!res.ok) return null;
  return readJson<CohRecommendationDetail>(
    res,
    (b) => isRecord(b) && typeof b.recommendationId === 'string',
  );
};

/** Whether the Hub is enabled for the account. Defaults to not-enabled on any error. */
export const fetchCohStatus = async (accountId: string): Promise<CohStatus> => {
  const params = new URLSearchParams({ accountId });
  const res = await apiFetch(`/api/xamops/cost-optimization-hub/status?${params}`);
  if (!res.ok) return { enabled: false };
  const body = await readJson<CohStatus>(res, (b) => isRecord(b) && typeof b.enabled === 'boolean');
  return body ?? { enabled: false };
};
