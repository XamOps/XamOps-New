/**
 * Vertical rightsizing engine client for the published contract
 * (.agents/tasks/java-vertical-rightsizer-2026-10-09/api-contract.md; docs/cloudk8s/ui-revamp-design.md §11.5).
 *
 * Paths, query parameters, request bodies and error parsing live here; `verticalRecommendationsAdapter.ts`
 * turns responses into the view model. Every call carries `accountId` (the AWS account number,
 * never a primary key) and `region`.
 *
 * Endpoint 1 is implemented on the engine branch; 2-4 are contract only. Writes (2, 3) are never
 * sent while `VERTICAL_WRITES_CONFIRMED` is false (D16): the capability model blocks them first.
 */
import { k8sRequest, K8sApiError } from './k8sAutomationApi';
import type {
  ActionsPageWire,
  ApplyRequest,
  ApplySubmissionWire,
  VerticalPolicyWire,
  VerticalStatusWire,
  WorkloadRefWire,
} from '../types/verticalRecommendations';

export interface VerticalTarget {
  cluster: string;
  accountId: string;
  region: string;
}

const enc = encodeURIComponent;
const BASE = '/api/eks/automation/vertical';

const query = (t: VerticalTarget) => `accountId=${enc(t.accountId)}&region=${enc(t.region)}`;
const workloadPath = (t: VerticalTarget, ref: WorkloadRefWire) =>
  `${BASE}/${enc(t.cluster)}/workloads/${enc(ref.namespace)}/${enc(ref.kind)}/${enc(ref.name)}`;

/** Endpoint 1: engine status, gate reasons and workloads. */
export function getVerticalStatus(t: VerticalTarget, init?: RequestInit): Promise<VerticalStatusWire> {
  return k8sRequest(`${BASE}/${enc(t.cluster)}?${query(t)}`, init);
}

/** Endpoint 2: set one workload's policy (contract only). */
export function putVerticalPolicy(t: VerticalTarget, ref: WorkloadRefWire, policy: VerticalPolicyWire): Promise<VerticalPolicyWire> {
  return k8sRequest(`${workloadPath(t, ref)}/policy?${query(t)}`, { method: 'PUT', body: JSON.stringify(policy) });
}

/** Endpoint 3: submit one recommendation (contract only). A `202` is a submission, never a result. */
export function postVerticalApply(t: VerticalTarget, ref: WorkloadRefWire, body: ApplyRequest): Promise<ApplySubmissionWire> {
  return k8sRequest(`${workloadPath(t, ref)}/apply?${query(t)}`, { method: 'POST', body: JSON.stringify(body) });
}

/** Endpoint 4: the actions feed, newest first (contract only). */
export function getVerticalActions(
  t: VerticalTarget,
  page: { limit?: number; before?: string | null } = {},
  init?: RequestInit,
): Promise<ActionsPageWire> {
  const limit = page.limit ?? 50;
  const before = page.before ? `&before=${enc(page.before)}` : '';
  return k8sRequest(`${BASE}/${enc(t.cluster)}/actions?${query(t)}&limit=${limit}${before}`, init);
}

/**
 * How a failed engine call reads, decided by `code`, never by HTTP status alone (§11.2, E23):
 * - `not-deployed`: a codeless 404 (the endpoint doesn't exist here) → V6
 * - `not-registered`: `K8S_CLUSTER_NOT_REGISTERED` → V8
 * - `no-account-access`: a codeless 403 or `K8S_NO_ACCOUNT_ACCESS` → the page-level message
 * - `failed`: anything else (network, timeout, 5xx with or without a code, 400, unparseable) → V5
 */
export type VerticalFailure = 'not-deployed' | 'not-registered' | 'no-account-access' | 'failed';

export function classifyVerticalFailure(error: unknown): VerticalFailure {
  if (!(error instanceof K8sApiError)) return 'failed';
  if (error.code === 'K8S_CLUSTER_NOT_REGISTERED') return 'not-registered';
  if (error.code === 'K8S_NO_ACCOUNT_ACCESS') return 'no-account-access';
  if (error.code === null && error.status === 404) return 'not-deployed';
  if (error.code === null && error.status === 403) return 'no-account-access';
  return 'failed';
}
