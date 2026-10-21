/**
 * The status mapping every Phase-2 read shares (usage, sparklines, HPA history, the activity
 * summary), as `verticalRightsizerApi.ts` reads endpoint 1: a `401` is a lapsed session; a typed
 * `404` `K8S_CLUSTER_NOT_REGISTERED` is a cluster this environment doesn't know, and any other typed
 * `404` (a workload or HPA the cluster doesn't have) a refusal naming its field; an untyped `404`
 * means this backend doesn't serve the endpoint (`not-deployed`, the panel isn't rendered); a typed
 * `403` or `400` is a refusal with its code, and an untyped `403` (the app's own "Access is denied") is
 * the no-account-access refusal; anything else is an error carrying the server's message.
 * A `200` body the reader can't recognise is an error too, never an empty chart.
 */
import apiClient from '../utils/apiClient';
import { parseCode } from './verticalRightsizerAdapter';
import type { InsightReadResult } from '../types/automationInsights';

interface RefusalWire {
  code?: string | null;
  message?: string | null;
  field?: string | null;
}

const readJson = async <T>(response: Response): Promise<T | null> => {
  try {
    const raw = await response.text();
    if (!raw || raw.trim() === '') return null;
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
};

const refusalMessage = (body: RefusalWire | null, fallback: string): string => {
  const message = body?.message;
  return message && message.trim() !== '' ? message : fallback;
};

export interface InsightReadSpec<W, V> {
  /** Recognises the 200 body; anything else is an error. */
  isWire: (body: unknown) => body is W;
  adapt: (wire: W) => V;
  /** What answered, for the error words ("The usage feed"). */
  what: string;
  /** Aborts the request when the page unmounts or the query changes. */
  signal?: AbortSignal;
}

/** One GET of a Phase-2 read. Never throws. */
export async function readInsight<W, V>(url: string, spec: InsightReadSpec<W, V>): Promise<InsightReadResult<V>> {
  let response: Response;
  try {
    response = await apiClient.fetch(url, spec.signal ? { method: 'GET', signal: spec.signal } : { method: 'GET' });
  } catch (e) {
    return { kind: 'error', message: e instanceof Error ? e.message : 'The request could not be sent.' };
  }

  if (response.status === 401) return { kind: 'unauthenticated' };

  if (response.ok) {
    const body = await readJson<unknown>(response);
    if (!spec.isWire(body)) return { kind: 'error', message: `${spec.what} answered in a shape XamOps can't read.` };
    return { kind: 'ok', view: spec.adapt(body) };
  }

  const refusal = await readJson<RefusalWire>(response);
  const code = parseCode(refusal?.code);
  const field = typeof refusal?.field === 'string' && refusal.field.trim() !== '' ? refusal.field.trim() : null;
  if (response.status === 404) {
    if (!code) return { kind: 'not-deployed' };
    if (code.kind === 'known' && code.code === 'CLUSTER_NOT_REGISTERED') {
      return {
        kind: 'cluster-not-registered',
        message: refusalMessage(refusal, 'This cluster is not registered with XamOps in this environment.'),
      };
    }
    return { kind: 'refused', code, message: refusalMessage(refusal, 'XamOps found nothing to read for this.'), field };
  }
  if (code && (response.status === 403 || response.status === 400)) {
    return { kind: 'refused', code, message: refusalMessage(refusal, 'The read was refused.'), field };
  }
  if (response.status === 403) {
    // The usage and HPA history reads answer an account the caller isn't entitled to as endpoint 1
    // does, with the app's untyped {"message":"Access is denied"}: the same refusal the activity reads
    // type as K8S_NO_ACCOUNT_ACCESS, which a retry can't change.
    return { kind: 'refused', code: parseCode('K8S_NO_ACCOUNT_ACCESS')!, message: NO_ACCOUNT_ACCESS_MESSAGE, field: null };
  }
  return { kind: 'error', message: refusalMessage(refusal, `${spec.what} returned HTTP ${response.status}.`) };
}

/** What an untyped 403 means on these reads. */
export const NO_ACCOUNT_ACCESS_MESSAGE = "You don't have access to this AWS account.";

/** A plain object (not null, not an array). */
export const isObject = (body: unknown): body is Record<string, unknown> =>
  typeof body === 'object' && body !== null && !Array.isArray(body);
