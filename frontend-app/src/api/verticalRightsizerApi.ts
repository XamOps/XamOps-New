/**
 * Typed client for the workload vertical rightsizer (`/api/eks/automation/vertical/**`).
 *
 * Status codes are read off the response rather than inferred from a thrown message, because the
 * two interesting ones are not errors:
 *
 *  - `404` on the apply endpoint means the write path **is not deployed in this environment yet**,
 *    which the UI states plainly instead of showing a failure;
 *  - `403` carries a gate code, which disables a control and explains why — it is a refusal, not a
 *    fault.
 */

import apiClient from '../utils/apiClient';
import { adaptEngineResponse, parseCode } from './verticalRightsizerAdapter';
import type {
  ApplyOutcomeWire,
  ApplyRequest,
  ApplyResult,
  VerticalEngineResponseWire,
  VerticalReadResult,
  VerticalRefusalWire,
  VerticalWorkloadRefWire,
} from '../types/verticalRightsizer';

const BASE = '/api/eks/automation/vertical';

const seg = (value: string): string => encodeURIComponent(value);

export const verticalReadUrl = (cluster: string, accountId: string, region: string): string =>
  `${BASE}/${seg(cluster)}?accountId=${seg(accountId)}&region=${seg(region)}`;

export const verticalApplyUrl = (
  cluster: string,
  ref: VerticalWorkloadRefWire,
  accountId: string,
  region: string,
): string =>
  `${BASE}/${seg(cluster)}/workloads/${seg(ref.namespace)}/${seg(ref.kind)}/${seg(ref.name)}/apply` +
  `?accountId=${seg(accountId)}&region=${seg(region)}`;

const readJson = async <T>(response: Response): Promise<T | null> => {
  try {
    const text = await response.text();
    if (!text || text.trim() === '') return null;
    return JSON.parse(text) as T;
  } catch {
    return null;
  }
};

const refusalMessage = (body: VerticalRefusalWire | null, fallback: string): string => {
  const message = body?.message;
  return message && message.trim() !== '' ? message : fallback;
};

/** Engine status, gate reasons and recommendations for one cluster. Read-only. */
export const fetchVerticalEngine = async (
  cluster: string,
  accountId: string,
  region: string,
): Promise<VerticalReadResult> => {
  let response: Response;
  try {
    response = await apiClient.fetch(verticalReadUrl(cluster, accountId, region), { method: 'GET' });
  } catch (e) {
    return { kind: 'error', message: e instanceof Error ? e.message : 'The request could not be sent.' };
  }

  if (response.status === 401) return { kind: 'unauthenticated' };

  if (response.ok) {
    const body = await readJson<VerticalEngineResponseWire>(response);
    if (!body || !body.engine) {
      return { kind: 'error', message: 'The engine returned no status.' };
    }
    return { kind: 'ok', view: adaptEngineResponse(body) };
  }

  const refusal = await readJson<VerticalRefusalWire>(response);

  if (response.status === 404) {
    return {
      kind: 'cluster-not-registered',
      message: refusalMessage(refusal, 'This cluster is not registered with XamOps in this environment.'),
    };
  }
  if (response.status === 403) {
    const code = parseCode(refusal?.code);
    if (code) {
      return { kind: 'refused', code, message: refusalMessage(refusal, 'This cluster is not available.') };
    }
  }
  return {
    kind: 'error',
    message: refusalMessage(refusal, `The engine returned HTTP ${response.status}.`),
  };
};

interface ApplyAcceptedWire {
  correlationId?: string | null;
  state?: string | null;
  dryRun?: boolean | null;
  result?: ApplyOutcomeWire | null;
}

/**
 * Submits one recommendation.
 *
 * The reply is a **submission**. The direct execution path completes inside the request and so
 * answers with an additive `result` carrying the real outcome and the restart-free proof; the agent
 * path will not, and then `result` is `null`. Either way the only value that may be rendered as a
 * success is `result.terminal === 'APPLIED'` — nothing here infers one.
 */
export const applyVerticalRecommendation = async (
  cluster: string,
  ref: VerticalWorkloadRefWire,
  accountId: string,
  region: string,
  request: ApplyRequest,
): Promise<ApplyResult> => {
  let response: Response;
  try {
    response = await apiClient.fetch(verticalApplyUrl(cluster, ref, accountId, region), {
      method: 'POST',
      body: JSON.stringify(request),
    });
  } catch (e) {
    return { kind: 'error', message: e instanceof Error ? e.message : 'The request could not be sent.' };
  }

  // Two different 404s now that the handler exists:
  //  - a typed K8S_CLUSTER_NOT_REGISTERED, which is a setup step on this cluster;
  //  - an untyped one from the dispatcher, which means this environment is running a build where
  //    the write path is not mapped. Both are deployment states, not failures, and they have
  //    different remedies.
  if (response.status === 404) {
    const notFound = await readJson<VerticalRefusalWire>(response);
    const code = parseCode(notFound?.code);
    if (code) {
      return {
        kind: 'cluster-not-registered',
        message: refusalMessage(notFound,
          'This cluster is not registered with XamOps in this environment.'),
      };
    }
    return { kind: 'writes-not-deployed' };
  }

  if (response.ok) {
    const body = await readJson<ApplyAcceptedWire>(response);
    return {
      kind: 'submitted',
      correlationId: body?.correlationId ?? null,
      // Never invent a state the server did not send (review F6); the tab renders null as a dash.
      state: body?.state ?? null,
      dryRun: body?.dryRun ?? null,
      result: body?.result ?? null,
    };
  }

  const refusal = await readJson<VerticalRefusalWire>(response);
  const code = parseCode(refusal?.code);
  // 409 is another real apply to the same workload still running (review-2 SF5).
  if (code && (response.status === 403 || response.status === 400 || response.status === 409)) {
    const detail = typeof refusal?.detail === 'string' && refusal.detail.trim() !== '' ? refusal.detail.trim() : null;
    return { kind: 'refused', code, message: refusalMessage(refusal, 'The apply was refused.'), detail };
  }
  return {
    kind: 'error',
    message: refusalMessage(refusal, `The apply endpoint returned HTTP ${response.status}.`),
  };
};
