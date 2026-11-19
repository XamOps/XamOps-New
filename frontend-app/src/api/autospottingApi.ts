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
  /** GCP: preemptions recorded in the last 7 days; null when nothing is tracked for the group yet. */
  preemptions7d?: number | null;
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
  /** GCP only: the edited rolling-update fields of a MIG_POLICY_UPDATE or policy-only CONFIG_APPLY plan. */
  policyEdit?: Record<string, unknown> | null;
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
  feeBasis: { contractId?: string | null; reason?: string; sharePct?: number; monthlyCap?: number | null } | null;
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
  /** A pre-serialized JSON body, for payloads whose decimals must not pass through a JS number. */
  rawBody?: string;
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
    body: opts.rawBody !== undefined ? opts.rawBody : opts.body === undefined ? undefined : JSON.stringify(opts.body),
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
// Phase 14: Commitments, Analytics, Activity, potential
// ---------------------------------------------------------------------------
/** The legacy RI/SP coverage body, as the backend returns it (snake_case). */
export interface RispCoverageBody {
  collected_at?: string | null;
  total_ri_utilization_percent?: number;
  total_sp_utilization_percent?: number;
  ri_coverages?: Array<{ instance_type?: string; region?: string; availability_zone?: string; coverage_percent?: number }>;
  sp_coverages?: Array<{ plan_type?: string; coverage_percent?: number }>;
}
export interface CommitmentSettings {
  respectRiSpCommitments: boolean;
  riSpUtilizationThreshold: number;
}
export interface AnalyticsFigures {
  spotLaunches: number | null;
  launchFailures: number | null;
  failuresByCause: Record<string, number>;
  interruptions: number | null;
  rebalanceRecommendations: number | null;
  successRate: number | null;
}
export interface GroupAnalytics {
  groupId: string;
  groupKey: string;
  name: string;
  location: string | null;
  figures: AnalyticsFigures;
  byType: Array<{ instanceType: string; figures: AnalyticsFigures }>;
}
export interface Analytics {
  from: string;
  to: string;
  /** Metric names with no source for the account. A listed metric is "Not tracked", never 0. */
  notTracked: string[];
  totals: AnalyticsFigures;
  groups: GroupAnalytics[];
}
export interface AuditEntry {
  id: number;
  occurredAt: string;
  eventType: string;
  actorType: string;
  actorRole: string | null;
  operationId: string | null;
  planId: string | null;
  groupId: string | null;
  groupKey: string | null;
  action: string;
  outcome: string;
  errorCode: string | null;
}
export interface Potential {
  potentialMonthlySavings: number | null;
  pricedGroups: number;
  unpricedGroups: number;
  ineligibleGroups: number;
  partial: boolean;
  asOf: string | null;
}
export interface StackCapability {
  code: string;
  label: string;
}
/** What the account's stack lacks for AutoSpotting (design-aws.md §10.1). */
export interface StackCapabilities {
  applicable: boolean;
  templateVersion: string | null;
  latestVersion: string;
  updateRequired: boolean;
  missing: StackCapability[];
}
export const getStackCapabilities = (accountId: number): Promise<StackCapabilities> =>
  request('GET', `${V2}/accounts/${accountId}/stack-capabilities`);
/** The AWS event pipeline's coverage per region (design-aws.md §9.4). Only COVERED lets a group convert. */
export interface RegionCoverage {
  applicable: boolean;
  regions: { region: string; status: 'COVERED' | 'MISSING' | 'SKIPPED' | 'UNKNOWN'; checkedAt: string; lastEventAt: string | null }[];
}
export const getRegionCoverage = (accountId: number): Promise<RegionCoverage> =>
  request('GET', `${V2}/accounts/${accountId}/region-coverage`);
export const getCommitmentCoverage = (accountId: number): Promise<RispCoverageBody> =>
  request('GET', `${V2}/accounts/${accountId}/commitments/coverage`);
export const getCommitmentSettings = (accountId: number): Promise<CommitmentSettings> =>
  request('GET', `${V2}/accounts/${accountId}/commitments/settings`);
export const saveCommitmentSettings = (
  accountId: number,
  settings: CommitmentSettings,
  idempotencyKey: string = newIdempotencyKey(),
): Promise<CommitmentSettings> =>
  request('PUT', `${V2}/accounts/${accountId}/commitments/settings/risp-respect`, {
    body: settings,
    idempotencyKey,
  });
/** `from` and `to` are `YYYY-MM-DD` (UTC days); the default is the last 30 days, the maximum 92. */
export const getAnalytics = (accountId: number, range: { from?: string; to?: string } = {}): Promise<Analytics> =>
  request('GET', `${V2}/accounts/${accountId}/analytics${query(range)}`);
export const getAudit = (
  accountId: number,
  opts: { groupId?: string; from?: string; to?: string; limit?: number } = {},
): Promise<AuditEntry[]> => request('GET', `${V2}/accounts/${accountId}/audit${query(opts)}`);
/** A ledger amount: a decimal string with its currency (never a JSON number). */
export interface LedgerMoney {
  amount: string;
  currency: string;
}
/** The open month's accrual for the "Accruing MTD (not final)" KPI (plan-phase-17.md 17.4). */
export interface LedgerSummary {
  month: string;
  accruingNotFinal: LedgerMoney;
  label: 'Accruing (not final)';
}
/** A group's measured net for one month; the label says what kind of figure it is. */
export interface LedgerGroupMonth {
  month: string;
  status: 'OPEN' | 'CLOSED' | 'INVOICED';
  amount: LedgerMoney;
  label: 'Accruing (not final)' | 'Not invoiced';
}
export const getLedgerSummary = (accountId: number): Promise<LedgerSummary> =>
  request('GET', `${V2}/accounts/${accountId}/savings/summary`);
export const getGroupSavings = (accountId: number, groupId: string): Promise<LedgerGroupMonth[]> =>
  request('GET', `${V2}/accounts/${accountId}/groups/${encodeURIComponent(groupId)}/savings`);
export const getPotential = (accountId: number): Promise<Potential> =>
  request('GET', `${V2}/accounts/${accountId}/groups/potential`);
/** The legacy URLs the old AWS page still uses until Phase 36 (the shell calls only v2). */
export const legacyRispCoveragePath = (awsAccountId: string): string =>
  `/api/autospotting/analytics/risp-coverage/${awsAccountId}`;
export const legacySettingsPath = (awsAccountId: string): string => `/api/autospotting/settings/${awsAccountId}`;
export const legacyRispRespectPath = (awsAccountId: string): string =>
  `/api/autospotting/settings/${awsAccountId}/risp-respect`;
function query(params: Record<string, string | number | undefined>): string {
  const p = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== '') p.set(k, String(v));
  });
  const q = p.toString();
  return q ? `?${q}` : '';
}
// ---------------------------------------------------------------------------
// Operator API (/api/xamops/autospotting-admin, Phase 09). Super admins and XamOps admins only.
// ---------------------------------------------------------------------------
const ADMIN = '/api/xamops/autospotting-admin';
export interface OperatorSwitch {
  scopeKey: string;
  propertyValue: boolean | null;
  engaged: boolean;
  effectiveValue: boolean | null;
  reason: string | null;
  changedBy: string | null;
  changedAt: string | null;
  version: number;
}
export interface OperatorOperations {
  operations: Operation[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
export const listSwitches = (): Promise<OperatorSwitch[]> => request('GET', `${ADMIN}/switches`);
export const setSwitch = (
  scopeKey: string,
  change: { engaged: boolean; expectedVersion: number; reason: string },
  idempotencyKey: string = newIdempotencyKey(),
): Promise<OperatorSwitch> =>
  request('PUT', `${ADMIN}/switches/${encodeURIComponent(scopeKey)}`, { body: change, idempotencyKey });
export const listOperatorOperations = (
  opts: { waitReason?: string; provider?: string; page?: number; size?: number } = {},
): Promise<OperatorOperations> => request('GET', `${ADMIN}/operations${query(opts)}`);
export const pauseGroup = (
  groupId: string,
  reason: string,
  idempotencyKey: string = newIdempotencyKey(),
): Promise<{ groupId: string; state: string }> =>
  request('POST', `${ADMIN}/groups/${encodeURIComponent(groupId)}/pause`, { body: { reason }, idempotencyKey });
export const resumeGroup = (
  groupId: string,
  reason: string,
  idempotencyKey: string = newIdempotencyKey(),
): Promise<{ groupId: string; state: string }> =>
  request('POST', `${ADMIN}/groups/${encodeURIComponent(groupId)}/resume`, { body: { reason }, idempotencyKey });
// ---------------------------------------------------------------------------
// Allowlist with billing basis (Phase 15, design-core.md section 6.1). Super admins and XamOps admins only.
// ---------------------------------------------------------------------------
export type BillingBasis = 'CONTRACT' | 'NO_FEE';
export interface AllowlistRow {
  cloudAccountId: number;
  provider: string;
  accountName: string | null;
  clientId: number | null;
  billingBasis: BillingBasis;
  contractId: string | null;
  contractInForce: boolean;
  reason: string;
  addedBy: string;
  addedAt: string;
}
export interface AllowlistOptions {
  cloudAccountId: number;
  provider: string;
  accountName: string | null;
  clientId: number | null;
  contractInForce: boolean;
  contractId: string | null;
}
export const listAllowlist = (): Promise<AllowlistRow[]> => request('GET', `${ADMIN}/allowlist`);
export const getAllowlistOptions = (cloudAccountId: number): Promise<AllowlistOptions> =>
  request('GET', `${ADMIN}/allowlist/options/${encodeURIComponent(String(cloudAccountId))}`);
export const addToAllowlist = (
  body: { cloudAccountId: number; billingBasis: BillingBasis; reason: string },
  idempotencyKey: string = newIdempotencyKey(),
): Promise<AllowlistRow> => request('POST', `${ADMIN}/allowlist`, { body, idempotencyKey });
export const removeFromAllowlist = (
  cloudAccountId: number,
  reason: string,
  idempotencyKey: string = newIdempotencyKey(),
): Promise<void> =>
  request('DELETE', `${ADMIN}/allowlist/${encodeURIComponent(String(cloudAccountId))}`, {
    body: { reason },
    idempotencyKey,
  });
// ---------------------------------------------------------------------------
// Operator: AWS event dead-letter queue (Phase 22, design-aws.md section 9.8). No message bodies.
// ---------------------------------------------------------------------------
export interface DlqEntry {
  messageId: string;
  detailType: string | null;
  account: string | null;
  groupId: string | null;
  sentAt: string | null;
  receiveCount: number;
}
export interface DlqSummary {
  configured: boolean;
  queue: string | null;
  depth: number;
  messages: DlqEntry[];
  redriveRunning: boolean;
}
export interface DlqRedriveResult {
  mode: 'MOVE_TASK' | 'RESEND';
  taskHandle: string | null;
  moved: number;
}
export const getEventsDlq = (): Promise<DlqSummary> => request('GET', `${ADMIN}/events/dlq?provider=aws`);
export const redriveEventsDlq = (
  reason: string,
  idempotencyKey: string = newIdempotencyKey(),
): Promise<DlqRedriveResult> =>
  request('POST', `${ADMIN}/events/dlq/redrive?provider=aws`, { body: { reason }, idempotencyKey });
// ---------------------------------------------------------------------------
// BillOps admin: contracts and go-live (Phase 15, ledger design section 11.4). BillOps and super admins only.
// Percentages and amounts travel as decimal strings; the form never turns them into numbers.
// ---------------------------------------------------------------------------
const BILLOPS_ADMIN = '/api/xamops/billops-admin/autospotting';
export interface ContractRow {
  id: string;
  clientId: number;
  sharePct: string;
  monthlyCap: { amount: string; currency: string } | null;
  declaredDiscountPct: string | null;
  discountDeclaration: 'DECLARED' | 'NOT_DECLARED';
  effectiveFrom: string;
  effectiveTo: string | null;
  createdBy: string;
  createdAt: string;
  inForce: boolean;
}
export interface NewContract {
  sharePct: string;
  monthlyCap: string | null;
  discountDeclaration: 'DECLARED' | 'NOT_DECLARED';
  declaredDiscountPct: string | null;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  reason: string;
}
export interface GoLiveRow {
  provider: string;
  goLiveAt: string;
  setBy: string;
  setAt: string;
}
export const listContracts = (clientId: number): Promise<ContractRow[]> =>
  request('GET', `${BILLOPS_ADMIN}/clients/${encodeURIComponent(String(clientId))}/contracts`);
/**
 * The body is serialized by hand so a decimal string becomes a JSON number with its digits intact.
 * JSON.stringify of a JS number would round-trip through a double.
 */
const DECIMAL = /^(0|[1-9]\d{0,11})(\.\d{1,6})?$/;
export const isDecimalText = (v: string): boolean => DECIMAL.test(v);
export const createContract = (
  clientId: number,
  c: NewContract,
  idempotencyKey: string = newIdempotencyKey(),
): Promise<ContractRow> => {
  // The decimals are spliced into the JSON text, so each one must be plain digits first.
  for (const v of [c.sharePct, c.monthlyCap, c.declaredDiscountPct]) {
    if (v !== null && !DECIMAL.test(v)) {
      return Promise.reject(new AutoSpottingApiError({ status: 422, code: 'CHECK_FAILED', detail: 'A number is not valid.' }));
    }
  }
  return request('POST', `${BILLOPS_ADMIN}/clients/${encodeURIComponent(String(clientId))}/contracts`, {
    rawBody: `{"sharePct":${c.sharePct},"monthlyCap":${c.monthlyCap ?? 'null'},"capCurrency":"USD",`
      + `"discountDeclaration":"${c.discountDeclaration}","declaredDiscountPct":${c.declaredDiscountPct ?? 'null'},`
      + `"effectiveFrom":${JSON.stringify(c.effectiveFrom)},"effectiveTo":${JSON.stringify(c.effectiveTo)},`
      + `"reason":${JSON.stringify(c.reason)}}`,
    idempotencyKey,
  });
};
export const endContract = (
  id: string,
  body: { effectiveTo: string | null; reason: string },
  idempotencyKey: string = newIdempotencyKey(),
): Promise<ContractRow> =>
  request('POST', `${BILLOPS_ADMIN}/contracts/${encodeURIComponent(id)}/end`, { body, idempotencyKey });
export const listGoLives = (): Promise<GoLiveRow[]> => request('GET', `${BILLOPS_ADMIN}/golive`);
export const setGoLive = (
  provider: string,
  body: { goLiveAt: string | null; reason: string },
  idempotencyKey: string = newIdempotencyKey(),
): Promise<GoLiveRow> =>
  request('POST', `${BILLOPS_ADMIN}/golive/${encodeURIComponent(provider.toLowerCase())}`, { body, idempotencyKey });
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

// ---------------------------------------------------------------------------
// Account deletion check and "Revert all" (design-core.md §7.5, PO-21)
// ---------------------------------------------------------------------------

export interface ManagedGroupRef {
  groupId: string;
  groupKey: string;
  state: string;
}

export interface DeletionCheck {
  /** Groups in a state that blocks account deletion (APPLYING, ACTIVE, FALLBACK, REVERTING). */
  blocking: ManagedGroupRef[];
  /** DEGRADED, PAUSED and FAILED groups: still converted, do not block (PO-21a, open). */
  stillConverted: ManagedGroupRef[];
  revertAllPath: string;
  deletable: boolean;
}

export interface RevertAllItem {
  groupId: string;
  groupKey: string | null;
  groupState: string | null;
  plan: Plan | null;
  errorCode: string | null;
  errorDetail: string | null;
  operationId: string | null;
  operationStatus: string | null;
}

export interface RevertAllBatch {
  batchId: string;
  accountId: number;
  items: RevertAllItem[];
  total: number;
  reverted: number;
  failed: number;
  alreadyReverting: string[];
}

export const getDeletionCheck = (accountId: number): Promise<DeletionCheck> =>
  request('GET', `${V2}/accounts/${accountId}/deletion-check`);

export const createRevertAll = (accountId: number, idempotencyKey: string, retry?: RetryOptions): Promise<RevertAllBatch> =>
  sendWithRetry('POST', `${V2}/accounts/${accountId}/revert-all`, {}, idempotencyKey, retry);

export const getRevertAll = (accountId: number, batchId: string): Promise<RevertAllBatch> =>
  request('GET', `${V2}/accounts/${accountId}/revert-all/${batchId}`);

export const confirmRevertAll = (
  accountId: number,
  batchId: string,
  req: { acknowledgedWarningIds: string[]; reason?: string },
  idempotencyKey: string,
  retry?: RetryOptions,
): Promise<RevertAllBatch> =>
  sendWithRetry('POST', `${V2}/accounts/${accountId}/revert-all/${batchId}/confirm`, req, idempotencyKey, retry);
