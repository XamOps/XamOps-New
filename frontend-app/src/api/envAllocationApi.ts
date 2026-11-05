import { apiFetch } from '../utils/apiClient';
import type { CostBasis } from './curInsightsApi';

/**
 * Environment cost allocation: the client's environments, their ordered rules, and runs of those
 * rules over months of the AWS bill. Mirrors `EnvAllocationController`.
 *
 * Every call throws {@link EnvAllocationError} on a non-OK response, carrying the server's own
 * sentence. These are actions a reader takes on purpose — saving a rule, running a month — so a
 * refusal has to be shown, not silently turned into an empty state.
 */

const BASE = '/api/xamops/env-allocation';

export class EnvAllocationError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

const fail = async (res: Response): Promise<never> => {
  let message = `Request failed (${res.status})`;
  try {
    const body = (await res.json()) as { error?: string; message?: string; detail?: string };
    message = body.error || body.message || body.detail || message;
  } catch {
    // Not JSON — keep the status sentence.
  }
  throw new EnvAllocationError(res.status, message);
};

const json = async <T>(res: Response): Promise<T> => {
  if (!res.ok) return fail(res);
  return (await res.json()) as T;
};

const send = (method: string, body?: unknown): RequestInit => ({
  method,
  body: body === undefined ? undefined : JSON.stringify(body),
  headers: { 'Content-Type': 'application/json' },
});

// ── Types ────────────────────────────────────────────────────────────────────

export type MatchField =
  | 'ANY'
  | 'SERVICE'
  | 'USAGE_TYPE'
  | 'REGION'
  | 'ACCOUNT'
  | 'CHARGE_TYPE'
  | 'RESOURCE_ID'
  | 'TAG'
  | 'LABEL';

export type MatchOperator = 'EQUALS' | 'CONTAINS' | 'STARTS_WITH' | 'EXISTS';

export type AllocationType = 'FIXED' | 'FROM_VALUE' | 'PROPORTIONAL';

export type AllocationKind = 'TAGGED' | 'RULE' | 'SPLIT' | 'PROPORTIONAL' | 'UNALLOCATED';

export interface Environment {
  id: number;
  name: string;
  color: string;
  matchValues: string[];
  sortOrder: number;
}

export interface EnvironmentInput {
  name: string;
  color: string;
  matchValues: string[];
  sortOrder?: number;
}

export interface Split {
  environmentId: number;
  environmentName?: string | null;
  percent: number;
}

export interface Rule {
  /** Null for a rule not saved yet; the server assigns one. */
  id: string | null;
  name: string;
  field: MatchField;
  operator: MatchOperator;
  value: string | null;
  tagKey: string | null;
  allocation: AllocationType;
  splits: Split[];
}

export interface DraftView {
  rules: Rule[];
  latestVersion: number;
  draftUpdatedAt: string | null;
  draftUpdatedBy: string | null;
  unpublishedChanges: boolean;
}

export interface VersionSummary {
  versionNumber: number;
  publishedAt: string;
  publishedBy: string | null;
  note: string | null;
  ruleCount: number;
}

export type RunStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';

export interface RunView {
  id: number;
  awsAccountId: string;
  period: string;
  basis: CostBasis;
  versionNumber: number;
  status: RunStatus;
  error: string | null;
  totalCost: number | null;
  allocatedCost: number | null;
  unallocatedCost: number | null;
  lineCount: number | null;
  queuedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  startedBy: string | null;
  locked: boolean;
  lockedAt: string | null;
  lockedBy: string | null;
}

export interface EnvironmentTotal {
  name: string;
  color: string | null;
  total: number;
  direct: number;
  split: number;
  /** Percent of the bill total, 0–100. */
  share: number;
  unallocated: boolean;
}

/** One resource's part of a breakdown row. A null id is the row's resource-less charges. */
export interface ResourceShare {
  resourceId: string | null;
  region: string | null;
  sourceCost: number;
  amount: number;
  lineCount: number;
}

export interface BreakdownRow {
  environment: string;
  color: string | null;
  kind: AllocationKind;
  ruleId: string | null;
  ruleName: string | null;
  rulePosition: number | null;
  service: string | null;
  percent: number | null;
  sourceCost: number;
  amount: number;
  lineCount: number;
  /**
   * The costliest resources in this row, capped server-side. Empty when the month was not read per
   * resource (no label or resource-id rule), or for a run stored before resources were kept.
   */
  resources: ResourceShare[];
  /** How many entries the row really has; more than `resources.length` when capped. */
  resourceCount: number;
}

export interface RuleTotal {
  ruleId: string;
  name: string;
  position: number;
  rule: Rule;
  matchedCost: number;
  matched: boolean;
}

export type BreakdownStatus = 'RUN' | 'PREVIEW' | 'RUNNING' | 'NOT_RUN' | 'NO_RULES' | 'NO_REPORT';

export interface Breakdown {
  status: BreakdownStatus;
  period: string;
  basis: CostBasis;
  versionNumber: number | null;
  latestVersion: number | null;
  run: RunView | null;
  pendingRun: RunView | null;
  totalCost: number | null;
  allocatedCost: number | null;
  unallocatedCost: number | null;
  environments: EnvironmentTotal[];
  rows: BreakdownRow[];
  rules: RuleTotal[];
  missingTagKeys: string[];
  message: string | null;
}

export interface AuditEntry {
  id: number;
  actor: string | null;
  action: string;
  entityType: string | null;
  entityRef: string | null;
  detail: string | null;
  createdAt: string;
}

// ── Environments ─────────────────────────────────────────────────────────────

export const fetchEnvironments = async (): Promise<Environment[]> =>
  json(await apiFetch(`${BASE}/environments`));

export const createEnvironment = async (input: EnvironmentInput): Promise<Environment> =>
  json(await apiFetch(`${BASE}/environments`, send('POST', input)));

export const updateEnvironment = async (id: number, input: EnvironmentInput): Promise<Environment> =>
  json(await apiFetch(`${BASE}/environments/${id}`, send('PUT', input)));

export const deleteEnvironment = async (id: number): Promise<void> => {
  const res = await apiFetch(`${BASE}/environments/${id}`, { method: 'DELETE' });
  if (!res.ok) await fail(res);
};

// ── Rules ────────────────────────────────────────────────────────────────────

export const fetchDraft = async (): Promise<DraftView> => json(await apiFetch(`${BASE}/rules`));

export const saveDraft = async (rules: Rule[]): Promise<DraftView> =>
  json(await apiFetch(`${BASE}/rules`, send('PUT', { rules })));

export const publishDraft = async (note: string): Promise<VersionSummary> =>
  json(await apiFetch(`${BASE}/rules/publish`, send('POST', { note })));

export const fetchVersions = async (): Promise<VersionSummary[]> =>
  json(await apiFetch(`${BASE}/versions`));

/** One published version in full: its rules, and the environments they named at the time. */
export interface VersionSnapshot {
  id: number;
  versionNumber: number;
  publishedAt: string;
  publishedBy: string | null;
  note: string | null;
  rules: Rule[];
  environments: { id: number; name: string; color: string; matchValues: string[] }[];
}

export const fetchVersion = async (versionNumber: number): Promise<VersionSnapshot> =>
  json(await apiFetch(`${BASE}/versions/${versionNumber}`));

// ── Breakdown and preview ────────────────────────────────────────────────────

const monthQuery = (period: string, basis: CostBasis): string => {
  const params = new URLSearchParams({ period });
  if (basis !== 'unblended') params.set('basis', basis);
  return params.toString();
};

export const fetchBreakdown = async (
  accountId: string,
  period: string,
  basis: CostBasis,
): Promise<Breakdown> =>
  json(
    await apiFetch(
      `${BASE}/breakdown/${encodeURIComponent(accountId)}?${monthQuery(period, basis)}`,
      // A live month is an Athena scan; give it longer than the default deadline.
      { timeoutMs: 120_000 },
    ),
  );

/**
 * Applies rules to a month without storing anything.
 *
 * @param rules unsaved rules to try; omit to preview the saved draft
 * @param useDraft false to preview the latest published version instead
 */
export const previewRules = async (
  accountId: string,
  period: string,
  basis: CostBasis,
  options: { rules?: Rule[]; useDraft?: boolean } = {},
): Promise<Breakdown> =>
  json(
    await apiFetch(
      `${BASE}/preview/${encodeURIComponent(accountId)}?${monthQuery(period, basis)}`,
      {
        ...send('POST', { useDraft: options.useDraft ?? true, rules: options.rules ?? null }),
        timeoutMs: 120_000,
      },
    ),
  );

// ── Values a rule can match on ───────────────────────────────────────────────

/** A match field that takes a value. AWS tags are not offered on the Cost page. */
export type PickerField = Exclude<MatchField, 'ANY' | 'TAG'>;

export interface MatchValue {
  value: string;
  /** Unblended cost behind the value in the month; null when not measured. */
  cost: number | null;
  /** Resources carrying a label; null for every other field. */
  resources: number | null;
}

export interface MatchValues {
  field: PickerField;
  tagKey: string | null;
  period: string;
  /** Largest cost first; labels and tag keys from the schema alphabetically. */
  values: MatchValue[];
  /** More values exist than were returned. Search narrows the list on the server. */
  truncated: boolean;
  /** Why the list is empty when the report cannot answer, else null. */
  message: string | null;
}

/**
 * The values one field takes on this account's bill for the month, for the rule form's pickers.
 *
 * @param search narrows a truncated list to values containing it
 */
export const fetchMatchValues = async (
  accountId: string,
  period: string,
  field: PickerField,
  options: { search?: string } = {},
): Promise<MatchValues> => {
  const params = new URLSearchParams({ field, period });
  if (options.search) params.set('q', options.search);
  return json(
    await apiFetch(`${BASE}/values/${encodeURIComponent(accountId)}?${params.toString()}`, {
      // An Athena scan of the month, like the breakdown.
      timeoutMs: 120_000,
    }),
  );
};

// ── Runs ─────────────────────────────────────────────────────────────────────

export const queueRuns = async (
  accountId: string,
  from: string,
  to: string,
  basis: CostBasis,
): Promise<RunView[]> =>
  json(await apiFetch(`${BASE}/runs`, send('POST', { accountId, from, to, basis })));

export const fetchRunHistory = async (accountId: string): Promise<RunView[]> =>
  json(await apiFetch(`${BASE}/runs/account/${encodeURIComponent(accountId)}`));

export const lockRun = async (runId: number): Promise<RunView> =>
  json(await apiFetch(`${BASE}/runs/${runId}/lock`, { method: 'POST' }));

export const unlockRun = async (runId: number): Promise<RunView> =>
  json(await apiFetch(`${BASE}/runs/${runId}/unlock`, { method: 'POST' }));

export const fetchAudit = async (): Promise<AuditEntry[]> => json(await apiFetch(`${BASE}/audit`));

/**
 * Downloads a run's audit export.
 *
 * Through `apiFetch` and a blob rather than a plain link, because the tenant and impersonation
 * headers ride on the request and an `<a href>` cannot send them.
 */
export const downloadRunExport = async (runId: number, format: 'csv' | 'pdf'): Promise<void> => {
  const res = await apiFetch(`${BASE}/runs/${runId}/export.${format}`, {
    // JSON as well, so a refusal can come back as the usual {error} body.
    headers: { Accept: `${format === 'csv' ? 'text/csv' : 'application/pdf'}, application/json` },
    timeoutMs: 120_000,
  });
  if (!res.ok) await fail(res);
  const blob = await res.blob();
  const disposition = res.headers.get('Content-Disposition') ?? '';
  const name = /filename="([^"]+)"/.exec(disposition)?.[1] ?? `environment-allocation-run${runId}.${format}`;
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};
