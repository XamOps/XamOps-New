/**
 * The only module that calls AutoSpotting endpoints (design-ux-ops.md §2.3, plan-phase-13 task 13.1).
 *
 * Calls the v2 API, plus the Phase A disable for legacy groups while a cloud's `v2MutationsEnabled`
 * is false. Every non-GET sends `Idempotency-Key` (kept per user action and reused on retry),
 * `Content-Type: application/json` and `X-XamOps-Intent: autospotting` (AC-SEC-01). Errors arrive as
 * RFC 9457 problem+json and are mapped to {@link AutoSpottingApiError}.
 */
import { apiFetch } from '../utils/apiClient';

const V2 = '/api/autospotting/v2';

// ---------------------------------------------------------------------------
// Types (mirror the v2 DTOs in xamops-service dto.autospotting.v2)
// ---------------------------------------------------------------------------

export interface ProviderConfig {
  enabled: boolean;
  conversionsEnabled: boolean;
  allowlistOnly: boolean;
  v2MutationsEnabled: boolean;
  beta: boolean;
}

export interface AutoSpottingConfig {
  shellEnabled: boolean;
  dryRun: boolean;
  providers: Record<string, ProviderConfig>;
  engagedSwitches: string[];
}

export interface CheckDto {
  id: string;
  code: string;
  text: string;
  errorKind?: string | null;
}

export interface GroupRow {
  id: string | null;
  provider: string;
  groupKey: string;
  location: string;
  name: string;
  state: string;
  managed: boolean;
  externalSpot: boolean;
  desired: number | null;
  min: number | null;
  max: number | null;
  instanceType: string | null;
  enableAllowed: boolean;
  enableBlockedBy: string | null;
  blockers: CheckDto[];
  warnings: CheckDto[];
  deferred: CheckDto[];
  monthlySavings: number | null;
  savingsUnknown: boolean;
  savingsLabel: string | null;
}

export interface FailedLocation {
  location: string;
  code: string;
}

export interface GroupList {
  items: GroupRow[];
  partial: boolean;
  failedLocations: FailedLocation[];
  asOf: string | null;
  blockedCounts?: Record<string, number>;
  kpis?: {
    groups: number;
    eligible: number;
    blocked: number;
    potentialMonthlySavings: number | null;
    savingsUnknownGroups: number;
  };
}

export interface Operation {
  operationId: string;
  groupId: string | null;
  kind: string;
  status: string;
  phase: string;
  step: number;
  waitReason: string | null;
  /** The op's progress JSON, as text. */
  progress: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  dryRun: boolean;
  deadlineAt: string | null;
  finishedAt: string | null;
}

export interface GroupDetail {
  group: GroupRow;
  configMode: string | null;
  driftStatus: string | null;
  lastErrorCode: string | null;
  transitions: Array<{
    fromState: string | null;
    toState: string;
    reasonCode: string | null;
    actorType: string | null;
    operationId: string | null;
    occurredAt: string;
  }>;
  currentOperation: Operation | null;
}

export interface Plan {
  planId: string;
  groupId: string | null;
  kind: string;
  status: string;
  version: number;
  liveFingerprint: string | null;
  /** The plan body document, as JSON text. Use {@link parsePlanBody}. */
  body: string | null;
  requiresFullSpotOptin: boolean;
  /** JSON array text of the warning ids that need acknowledgment. */
  warningIds: string | null;
  dryRun: boolean;
  expiresAt: string | null;
  createdAt: string | null;
  batchId: string | null;
}

export interface PlanRequest {
  kind: string;
  groupId?: string | null;
  groupRef?: string | null;
  profileId?: string | null;
}

export interface ConfirmRequest {
  planVersion: number;
  acknowledgedWarningIds: string[];
  fullSpotOptIn: boolean;
  reason?: string;
}

export interface PriceSource {
  source?: string;
  version?: string;
  asOf?: string;
  [key: string]: unknown;
}

export interface PlanEstimate {
  profile: string;
  monthlySavings: number | null;
  priceSources: PriceSource[] | null;
  label: string;
}

export interface PlanBodyDoc {
  current: Record<string, unknown> | null;
  target: Record<string, unknown> | null;
  diff: Array<{ field: string; from: string | null; to: string | null }>;
  onDemandFloor: Record<string, unknown> | null;
  feeBasis: { contractId?: string | null; reason?: string; sharePct?: number; monthlyCap?: number } | null;
  revertPath: { program?: string; method?: string; exact?: boolean } | null;
  rollout: {
    method?: string;
    instancesToReplace?: number;
    surge?: number;
    durationRange?: string;
    headroom?: string;
    quota?: unknown;
  } | null;
  estimates: PlanEstimate[];
}

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

export interface FieldError {
  field: string;
  message: string;
}

export class AutoSpottingApiError extends Error {
  readonly status: number;
  readonly code: string;
  /** The engaged switch scope named by the error's `switch` field, if any. */
  readonly switch: string | null;
  readonly retryable: boolean;
  readonly detail: string;
  readonly fieldErrors: FieldError[];
  readonly requestId: string | null;

  constructor(init: {
    status: number;
    code: string;
    detail: string;
    switch?: string | null;
    retryable?: boolean;
    fieldErrors?: FieldError[];
    requestId?: string | null;
  }) {
    super(init.detail || init.code);
    this.name = 'AutoSpottingApiError';
    this.status = init.status;
    this.code = init.code;
    this.detail = init.detail;
    this.switch = init.switch ?? null;
    this.retryable = init.retryable ?? false;
    this.fieldErrors = init.fieldErrors ?? [];
    this.requestId = init.requestId ?? null;
  }
}

/** The request never produced an HTTP response (network error or the client timeout). */
export const NETWORK_UNREACHABLE = 'NETWORK_UNREACHABLE';

/** Map a problem+json response body to the error the UI works with. */
export function mapProblem(status: number, body: unknown): AutoSpottingApiError {
  const p = (body && typeof body === 'object' ? body : {}) as Record<string, unknown>;
  const fieldErrors = Array.isArray(p.fieldErrors)
    ? (p.fieldErrors as Array<Record<string, unknown>>).map(f => ({
        field: String(f.field ?? ''),
        message: String(f.message ?? ''),
      }))
    : [];
  return new AutoSpottingApiError({
    status,
    code: typeof p.code === 'string' && p.code ? p.code : `HTTP_${status}`,
    detail: typeof p.detail === 'string' ? p.detail : '',
    switch: typeof p.switch === 'string' ? p.switch : null,
    retryable: p.retryable === true,
    fieldErrors,
    requestId: typeof p.requestId === 'string' ? p.requestId : null,
  });
}

// ---------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------

/** A fresh idempotency key. One per user action; reuse it for every retry of that action. */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.floor(Math.random() * 16);
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

interface RequestOptions {
  body?: unknown;
  idempotencyKey?: string;
  noCache?: boolean;
  timeoutMs?: number;
}

async function request<T>(method: string, path: string, opts: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = {};
  if (method !== 'GET') {
    headers['Idempotency-Key'] = opts.idempotencyKey ?? newIdempotencyKey();
    headers['Content-Type'] = 'application/json';
    headers['X-XamOps-Intent'] = 'autospotting';
  }
  if (opts.noCache) headers['Cache-Control'] = 'no-cache';
  const res = await apiFetch(path, {
    method,
    headers,
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
    timeoutMs: opts.timeoutMs,
  });
  if (!res.ok) {
    let body: unknown = null;
    try {
      body = await res.json();
    } catch {
      body = null;
    }
    throw mapProblem(res.status, body);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

const CLIENT_TIMEOUT_MS = 10_000;
const MAX_TRIES = 3;

export interface RetryOptions {
  retryDelayMs?: number;
}

/**
 * A non-GET that survives a lost response: on a network error or the 10 s client timeout the same
 * request is retried with the same `Idempotency-Key`, so the backend returns the existing result if
 * the first attempt landed (core §3.7). After 3 tries it throws {@link NETWORK_UNREACHABLE}.
 */
async function sendWithRetry<T>(
  method: string,
  path: string,
  body: unknown,
  idempotencyKey: string,
  retry: RetryOptions = {},
): Promise<T> {
  const delay = retry.retryDelayMs ?? 500;
  for (let attempt = 1; ; attempt++) {
    try {
      return await request<T>(method, path, { body, idempotencyKey, timeoutMs: CLIENT_TIMEOUT_MS });
    } catch (e) {
      if (e instanceof AutoSpottingApiError) throw e;
      if (attempt >= MAX_TRIES) {
        throw new AutoSpottingApiError({
          status: 0,
          code: NETWORK_UNREACHABLE,
          detail: 'Could not reach XamOps. Your request may have been received.',
          retryable: true,
        });
      }
      if (delay > 0) await new Promise(r => setTimeout(r, delay * attempt));
    }
  }
}

// ---------------------------------------------------------------------------
// Endpoints
// ---------------------------------------------------------------------------

export const getConfig = (): Promise<AutoSpottingConfig> => request('GET', `${V2}/config`);

export const listGroups = (accountId: number, opts: { noCache?: boolean } = {}): Promise<GroupList> =>
  request('GET', `${V2}/accounts/${accountId}/groups`, { noCache: opts.noCache });

export const getGroup = (accountId: number, groupId: string): Promise<GroupDetail> =>
  request('GET', `${V2}/accounts/${accountId}/groups/${encodeURIComponent(groupId)}`);

export const createPlan = (
  accountId: number,
  req: PlanRequest,
  idempotencyKey: string,
  retry?: RetryOptions,
): Promise<Plan> => sendWithRetry('POST', `${V2}/accounts/${accountId}/plans`, req, idempotencyKey, retry);

export const getPlan = (accountId: number, planId: string): Promise<Plan> =>
  request('GET', `${V2}/accounts/${accountId}/plans/${planId}`);

export const confirmPlan = (
  accountId: number,
  planId: string,
  req: ConfirmRequest,
  idempotencyKey: string,
  opts: RetryOptions & { dryRun?: boolean } = {},
): Promise<{ operationId: string; status: string }> =>
  sendWithRetry(
    'POST',
    `${V2}/accounts/${accountId}/plans/${planId}/confirm${opts.dryRun ? '?dryRun=true' : ''}`,
    req,
    idempotencyKey,
    opts,
  );

export const getOperation = (accountId: number, operationId: string): Promise<Operation> =>
  request('GET', `${V2}/accounts/${accountId}/operations/${operationId}`);

/**
 * The Phase A disable for a legacy group (a non-v2 call). Used only while the cloud's
 * `v2MutationsEnabled` is false. `providerAccountId` is the 12-digit AWS account id the legacy
 * endpoint takes, not the numeric XamOps account id.
 */
export const disableLegacyGroup = (
  providerAccountId: string,
  region: string,
  asgName: string,
  idempotencyKey: string = newIdempotencyKey(),
): Promise<unknown> =>
  request(
    'POST',
    `/api/autospotting/asgs/${encodeURIComponent(providerAccountId)}/disable?region=${encodeURIComponent(
      region,
    )}&asgName=${encodeURIComponent(asgName)}`,
    { idempotencyKey },
  );

// ---------------------------------------------------------------------------
// Parsing helpers
// ---------------------------------------------------------------------------

function parseJson<T>(text: string | null | undefined, fallback: T): T {
  if (!text) return fallback;
  try {
    const v = JSON.parse(text);
    return v === null || v === undefined ? fallback : (v as T);
  } catch {
    return fallback;
  }
}

/** The plan's stored body document, with safe defaults for every section. */
export function parsePlanBody(plan: Pick<Plan, 'body'>): PlanBodyDoc {
  const raw = parseJson<Partial<PlanBodyDoc>>(plan.body, {});
  return {
    current: raw.current ?? null,
    target: raw.target ?? null,
    diff: Array.isArray(raw.diff) ? raw.diff : [],
    onDemandFloor: raw.onDemandFloor ?? null,
    feeBasis: raw.feeBasis ?? null,
    revertPath: raw.revertPath ?? null,
    rollout: raw.rollout ?? null,
    estimates: Array.isArray(raw.estimates) ? raw.estimates : [],
  };
}

/** The warning ids a plan needs acknowledged. */
export function parseWarningIds(plan: Pick<Plan, 'warningIds'>): string[] {
  const ids = parseJson<unknown>(plan.warningIds, []);
  return Array.isArray(ids) ? ids.filter((i): i is string => typeof i === 'string') : [];
}
