/**
 * XamPulse typed API client. Uses the shared apiFetch (CSRF, tenant headers) and maps the XpErrorEnvelope to
 * XpApiError. Demo mode (explicit opt-in, see isXpDemo) answers from src/demo/xampulseDemo.ts and is never a
 * fallback for failed requests.
 */
import { apiFetch } from '../utils/apiClient';
import { demoRequest } from '../demo/xampulseDemo';
import { isDemoMode } from '../demo/demoConfig';
import { XpApiError } from './xampulseErrors';

export { XpApiError };
import type {
  XpAccountRef, XpChange, XpCloudWatchView, XpContainerSummary, XpEc2Facts, XpErrorEnvelope, XpEvent, XpForecast, XpHealthScore, XpHostLimits, XpHostRow, XpHostSummary,
  XpInsight, XpLogContextResponse, XpLogFacetsRequest, XpLogFacetsResponse, XpLogHistogramRequest,
  XpLogHistogramResponse, XpLogPatternsRequest, XpLogPatternsResponse, XpLogSearchRequest, XpLogSearchResponse,
  XpMetricCatalogEntry, XpMetricQueryRequest, XpMetricQueryResponse, XpOverview, XpPage, XpProcessRow,
  XpContainerRow, XpStatus, XpTailTicket, XpTailTicketRequest, XpTrustPanel, XpUsage, XpArchiveSearchRequest,
  XpArchiveEstimate, XpSavedView, XpInvestigation, XpPeers, XpExposure, XpCostPerf,
  XpRunbookCatalog, XpRunbookStarted, XpRunbookResult,
} from '../types/xampulse';

export const XP_BASE = '/api/xamops/xampulse';
const QUERY_TIMEOUT_MS = 20_000;

/**
 * Rule (Aditya, 2026-11-23): XamPulse shows real data to every user. Sample data is shown only in the demo
 * account's session (demo.xamops@xammer.in, the app-wide demo mode set at login in demo/demoConfig), so the demo
 * user sees the same kind of estate in XamPulse as in the rest of XamOps.
 * A production build never shows sample data to anyone else. VITE_XAMPULSE_DEMO / localStorage xp:demo=1 only work
 * on the local dev server, for automated screenshot tests; they are ignored in any built bundle.
 */
export function isXpDemo(): boolean {
  if (isDemoMode()) return true;
  let flag = false;
  let dev = false;
  try {
    const env = (import.meta as unknown as { env?: Record<string, string | boolean | undefined> }).env;
    flag = env?.VITE_XAMPULSE_DEMO === 'true';
    dev = env?.DEV === true;
  } catch {
    /* non-Vite runtime */
  }
  if (!dev) return false;
  if (flag) return true;
  try {
    return window.localStorage.getItem('xp:demo') === '1';
  } catch {
    return false;
  }
}

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

/** Pulls an XpErrorEnvelope out of a response body, tolerating non-JSON bodies. */
export function parseErrorBody(text: string, status: number): XpApiError {
  try {
    const parsed: unknown = JSON.parse(text);
    if (isRecord(parsed) && isRecord(parsed.error) && typeof parsed.error.code === 'string') {
      const e = parsed.error as unknown as XpErrorEnvelope['error'];
      return new XpApiError(e.code, e.message ?? 'Request failed', status, e.requestId ?? '', e.details);
    }
  } catch {
    /* fall through */
  }
  if (status === 403) return new XpApiError('XP_FORBIDDEN', 'You do not have access to this.', status);
  if (status === 404) return new XpApiError('XP_NOT_FOUND', 'Not found.', status);
  return new XpApiError('XP_UNKNOWN', `Request failed (HTTP ${status}).`, status);
}

export type XpQuery = Record<string, string | number | boolean | null | undefined | (string | number)[]>;

export function toQueryString(q?: XpQuery): string {
  if (!q) return '';
  const p = new URLSearchParams();
  Object.keys(q).forEach((k) => {
    const v = q[k];
    if (v === undefined || v === null || v === '') return;
    p.set(k, Array.isArray(v) ? v.join(',') : String(v));
  });
  const s = p.toString();
  return s ? `?${s}` : '';
}

export async function xpRequest<T>(method: 'GET' | 'POST' | 'PUT' | 'DELETE', path: string, opts: { query?: XpQuery; body?: unknown; signal?: AbortSignal } = {}): Promise<T> {
  if (isXpDemo()) return demoRequest<T>(method, path, opts.query, opts.body);
  const url = `${XP_BASE}${path}${toQueryString(opts.query)}`;
  const init: RequestInit & { timeoutMs?: number } = { method, signal: opts.signal, timeoutMs: QUERY_TIMEOUT_MS };
  if (opts.body !== undefined) init.body = JSON.stringify(opts.body);
  let res: Response;
  try {
    res = await apiFetch(url, init);
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e;
    throw new XpApiError('XP_STORE_UNAVAILABLE', 'Could not reach XamPulse. Check your connection and retry.', 0);
  }
  const text = await res.text();
  if (!res.ok) throw parseErrorBody(text, res.status);
  if (text.trim() === '') return {} as T;
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new XpApiError('XP_UNKNOWN', 'XamPulse returned an unreadable response.', res.status);
  }
}

// ---- reads ----
export const xpGet = <T>(path: string, query?: XpQuery, signal?: AbortSignal): Promise<T> => xpRequest<T>('GET', path, { query, signal });
export const xpPost = <T>(path: string, body: unknown, signal?: AbortSignal): Promise<T> => xpRequest<T>('POST', path, { body, signal });

export const xampulseApi = {
  status: () => xpGet<XpStatus>('/status'),
  accounts: () => xpGet<XpAccountRef[]>('/accounts'),
  overview: (accountIds: number[], from: string, to: string) => xpGet<XpOverview>('/overview', { accountIds, from, to }),
  hosts: (q: { accountIds: number[]; q?: string; state?: string; page?: number; size?: number; sort?: string }) => xpGet<XpPage<XpHostRow>>('/hosts', q),
  hostSummary: (accountId: number, instanceId: string, from: string, to: string, compareOffsetSec?: number) =>
    xpGet<XpHostSummary>(`/hosts/${encodeURIComponent(instanceId)}/summary`, { accountId, from, to, ...(compareOffsetSec ? { compareOffsetSec } : {}) }),
  // Throttle Radar: limits hit in the range, from agent data and CloudWatch data already fetched. No billed call.
  hostLimits: (accountId: number, instanceId: string, from: string, to: string) =>
    xpGet<XpHostLimits>(`/hosts/${encodeURIComponent(instanceId)}/limits`, { accountId, from, to }),
  containerSummary: (accountId: number, instanceId: string, name: string, from: string, to: string) =>
    xpGet<XpContainerSummary>(`/containers/${encodeURIComponent(instanceId)}/${encodeURIComponent(name)}/summary`, { accountId, from, to }),
  hostChanges: (accountId: number, instanceId: string, from: string, to: string) =>
    xpGet<XpChange[]>(`/hosts/${encodeURIComponent(instanceId)}/changes`, { accountId, from, to }),
  // Investigate: every usual explanation checked against measurements (no billed call).
  investigate: (accountId: number, instanceId: string, from: string, to: string) =>
    xpGet<XpInvestigation>(`/hosts/${encodeURIComponent(instanceId)}/investigate`, { accountId, from, to }),
  // Exposure: listening sockets against security groups (EC2 API, not billed). Cost: list price against use.
  hostExposure: (accountId: number, instanceId: string) =>
    xpGet<XpExposure>(`/hosts/${encodeURIComponent(instanceId)}/exposure`, { accountId }),
  hostCost: (accountId: number, instanceId: string, from: string, to: string) =>
    xpGet<XpCostPerf>(`/hosts/${encodeURIComponent(instanceId)}/cost`, { accountId, from, to }),
  runbooks: () => xpGet<XpRunbookCatalog>('/runbooks'),
  runRunbook: (accountId: number, instanceId: string, runbookId: string) =>
    xpRequest<XpRunbookStarted>('POST', `/hosts/${encodeURIComponent(instanceId)}/runbooks/${encodeURIComponent(runbookId)}`, { query: { accountId } }),
  runbookResult: (accountId: number, instanceId: string, commandId: string) =>
    xpGet<XpRunbookResult>(`/hosts/${encodeURIComponent(instanceId)}/runbooks/commands/${encodeURIComponent(commandId)}`, { accountId }),
  hostPeers: (accountId: number, instanceId: string, from: string, to: string) =>
    xpGet<XpPeers>(`/hosts/${encodeURIComponent(instanceId)}/peers`, { accountId, from, to }),
  hostAws: (accountId: number, instanceId: string) =>
    xpGet<XpEc2Facts>(`/hosts/${encodeURIComponent(instanceId)}/aws`, { accountId }),
  // Free: earlier CloudWatch fetches kept by XamOps. Never calls CloudWatch.
  hostCloudWatch: (accountId: number, instanceId: string, from: string, to: string) =>
    xpGet<XpCloudWatchView>(`/hosts/${encodeURIComponent(instanceId)}/cloudwatch`, { accountId, from, to }),
  // Billed to the customer's AWS account (GetMetricData); only on an explicit Refresh.
  refreshHostCloudWatch: (accountId: number, instanceId: string, from: string, to: string) =>
    xpRequest<XpCloudWatchView>('POST', `/hosts/${encodeURIComponent(instanceId)}/cloudwatch/refresh`, { query: { accountId, from, to } }),
  // Process samples are available when the host uses collection level T2; a 404 means no process data for this server/range.
  hostProcesses: (accountId: number, instanceId: string, at?: string) =>
    xpGet<XpProcessRow[]>(`/hosts/${encodeURIComponent(instanceId)}/processes`, { accountId, at }).catch((e: unknown) => {
      if (e instanceof XpApiError && e.status === 404) return [] as XpProcessRow[];
      throw e;
    }),
  containers: (q: { accountIds: number[]; q?: string; page?: number; size?: number; sort?: string }) => xpGet<XpPage<XpContainerRow>>('/containers', q),
  logSearch: (req: XpLogSearchRequest, signal?: AbortSignal) => xpPost<XpLogSearchResponse>('/logs/search', req, signal),
  logHistogram: (req: XpLogHistogramRequest, signal?: AbortSignal) => xpPost<XpLogHistogramResponse>('/logs/histogram', req, signal),
  logFacets: (req: XpLogFacetsRequest, signal?: AbortSignal) => xpPost<XpLogFacetsResponse>('/logs/facets', req, signal),
  logPatterns: (req: XpLogPatternsRequest, signal?: AbortSignal) => xpPost<XpLogPatternsResponse>('/logs/patterns', req, signal),
  logContext: (accountId: number, id: string, before = 20, after = 20) =>
    xpGet<XpLogContextResponse>('/logs/context', { accountId, id, before, after, sameSource: true }),
  tailTicket: (req: XpTailTicketRequest) => xpPost<XpTailTicket>('/logs/tail/tickets', req),
  archiveEstimate: (req: XpArchiveSearchRequest) => xpPost<XpArchiveEstimate>('/archive-searches', req).catch((e: unknown) => {
    // The estimate endpoint is not routed on the server yet; say so plainly instead of a bare "Not found".
    if (e instanceof XpApiError && e.status === 404) throw new XpApiError('XP_NOT_FOUND', 'Archive cost estimates are not available on this server yet.', 404, e.requestId);
    throw e;
  }),
  metricQuery: (req: XpMetricQueryRequest, signal?: AbortSignal) => xpPost<XpMetricQueryResponse>('/metrics/query', req, signal),
  metricCatalog: (accountId: number, q?: string) => xpGet<XpMetricCatalogEntry[]>('/metrics/catalog', { accountId, q }),
  events: (accountIds: number[], from: string, to: string, types?: string[]) => xpGet<XpEvent[]>('/events', { accountIds, from, to, types }),
  usage: (accountId: number, range = '7d') => xpGet<XpUsage>('/usage', { accountId, range }),
  insights: (accountIds: number[], from: string, to: string) => xpGet<XpInsight[]>('/insights/feed', { accountIds, from, to }),
  forecasts: (accountId: number) => xpGet<XpForecast[]>('/insights/forecasts', { accountId }),
  healthScore: (accountId: number) => xpGet<XpHealthScore>('/health-score', { accountId }),
  trust: (accountId: number) => xpGet<XpTrustPanel>('/trust', { accountId }),
  views: (accountId: number) => xpGet<XpSavedView[]>('/views', { accountId }),
  saveView: (accountId: number, v: XpSavedView) => xpRequest<XpSavedView>('POST', '/views', { query: { accountId }, body: v }),
};
