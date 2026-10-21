/**
 * Typed client for the activity feed (`GET /api/eks/automation/activity/{cluster}`, FEAT-007):
 * what XamOps changed, tested or was refused on one cluster, newest first. Read-only.
 *
 * Status codes are read off the response, as in `verticalRightsizerApi.ts`: a `401` is a lapsed
 * session; a typed `404` (`K8S_CLUSTER_NOT_REGISTERED`) is a cluster this environment doesn't know,
 * while an untyped `404` means this backend has no feed yet (`not-deployed`), so the page keeps its
 * latest-change view; a typed `403` is a refusal with its code; anything else is an error carrying
 * the server's message.
 */
import apiClient from '../utils/apiClient';
import { parseCode } from './verticalRightsizerAdapter';
import { adaptActivityPage, adaptActivitySummary } from './automationActivityAdapter';
import { readInsight, isObject } from './insightRead';
import type {
  ActivityFeatureWire,
  ActivityPageWire,
  ActivityQuery,
  ActivityReadResult,
  ActivitySummaryView,
  ActivitySummaryWire,
} from '../types/automationActivity';
import type { InsightReadResult } from '../types/automationInsights';
import type { VerticalRefusalWire } from '../types/verticalRightsizer';

const BASE = '/api/eks/automation/activity';

const seg = (value: string): string => encodeURIComponent(value);

/** The server's limits (AutomationActivityController). */
export const ACTIVITY_PAGE_LIMIT = 50;
export const ACTIVITY_QUERY_MAX = 100;

export const activityUrl = (cluster: string, accountId: string, region: string, query: ActivityQuery = {}): string => {
  const params = [`accountId=${seg(accountId)}`, `region=${seg(region)}`];
  if (query.features && query.features.length > 0) params.push(`feature=${seg(query.features.join(','))}`);
  if (query.results && query.results.length > 0) params.push(`result=${seg(query.results.join(','))}`);
  const q = query.q?.trim();
  if (q) params.push(`q=${seg(q.slice(0, ACTIVITY_QUERY_MAX))}`);
  if (query.target) {
    params.push(`namespace=${seg(query.target.namespace)}`, `kind=${seg(query.target.kind)}`, `name=${seg(query.target.name)}`);
  }
  if (query.from) params.push(`from=${seg(query.from)}`);
  if (query.to) params.push(`to=${seg(query.to)}`);
  params.push(`limit=${query.limit ?? ACTIVITY_PAGE_LIMIT}`);
  if (query.cursor) params.push(`cursor=${seg(query.cursor)}`);
  return `${BASE}/${seg(cluster)}?${params.join('&')}`;
};

const readJson = async <T>(response: Response): Promise<T | null> => {
  try {
    const raw = await response.text();
    if (!raw || raw.trim() === '') return null;
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
};

const refusalMessage = (body: VerticalRefusalWire | null, fallback: string): string => {
  const message = body?.message;
  return message && message.trim() !== '' ? message : fallback;
};

/** One page of one cluster's activity. */
export const fetchActivity = async (
  cluster: string,
  accountId: string,
  region: string,
  query: ActivityQuery = {},
): Promise<ActivityReadResult> => {
  let response: Response;
  try {
    response = await apiClient.fetch(activityUrl(cluster, accountId, region, query), { method: 'GET' });
  } catch (e) {
    return { kind: 'error', message: e instanceof Error ? e.message : 'The request could not be sent.' };
  }

  if (response.status === 401) return { kind: 'unauthenticated' };

  if (response.ok) {
    const body = await readJson<ActivityPageWire>(response);
    if (!body || typeof body !== 'object' || !Array.isArray(body.entries)) {
      return { kind: 'error', message: 'The activity feed answered without entries.' };
    }
    return { kind: 'ok', page: adaptActivityPage(body) };
  }

  const refusal = await readJson<VerticalRefusalWire>(response);
  const code = parseCode(refusal?.code);

  if (response.status === 404) {
    if (code) {
      return {
        kind: 'cluster-not-registered',
        message: refusalMessage(refusal, 'This cluster is not registered with XamOps in this environment.'),
      };
    }
    return { kind: 'not-deployed' };
  }
  if (response.status === 403 && code) {
    return { kind: 'refused', code, message: refusalMessage(refusal, 'This cluster is not available.') };
  }
  return {
    kind: 'error',
    message: refusalMessage(refusal, `The activity feed returned HTTP ${response.status}.`),
  };
};

/** The summary's day limit (AutomationActivityController: 1 to 30, default 7). */
export const SUMMARY_DAYS_MAX = 30;

export const activitySummaryUrl = (
  cluster: string,
  accountId: string,
  region: string,
  days: number,
  features: readonly ActivityFeatureWire[] = [],
): string => {
  const params = [`accountId=${seg(accountId)}`, `region=${seg(region)}`, `days=${Math.max(1, Math.min(SUMMARY_DAYS_MAX, Math.round(days)))}`];
  if (features.length > 0) params.push(`feature=${seg(features.join(','))}`);
  return `${BASE}/${seg(cluster)}/summary?${params.join('&')}`;
};

const isSummaryWire = (body: unknown): body is ActivitySummaryWire => isObject(body) && Array.isArray(body.days);

/**
 * Changes per IST day, by result (FEAT-005), counted with the feed's own result mapping. Days XamOps
 * can't count honestly are left out, never zero-filled.
 */
export const fetchActivitySummary = (
  cluster: string,
  accountId: string,
  region: string,
  days: number,
  features: readonly ActivityFeatureWire[] = [],
  signal?: AbortSignal,
): Promise<InsightReadResult<ActivitySummaryView>> =>
  readInsight(activitySummaryUrl(cluster, accountId, region, days, features), {
    isWire: isSummaryWire,
    adapt: adaptActivitySummary,
    what: 'The activity summary',
    signal,
  });
