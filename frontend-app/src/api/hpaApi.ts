/**
 * Typed client for the HPA automation endpoints (`/api/eks/automation/hpa/**`, FEAT-002).
 *
 * The read path reuses the vertical rightsizer endpoint (one `GET` per cluster carries the HPA
 * decision in each workload's `horizontal` block), so only the **apply** and the HPAs' **history**
 * (FEAT-004, read-only) live here:
 *
 *   POST /api/eks/automation/hpa/{cluster}/{namespace}/{name}/apply?accountId=&region=
 *   GET  /api/eks/automation/hpa/{cluster}/{namespace}/{hpaName}/history?accountId=&region=&window=
 *   GET  /api/eks/automation/hpa/{cluster}/history?accountId=&region=&window=&hpas=ns/name,…
 *
 * `{name}` is the workload (Deployment) name; the backend resolves the HPA that targets it. Status
 * codes are read off the response, because the two interesting ones are deployment states, not
 * faults: a typed `404` is "this cluster is not registered here" and an untyped `404` is "the HPA
 * write path is not mapped in this environment yet".
 */

import apiClient from '../utils/apiClient';
import { parseCode } from './verticalRightsizerAdapter';
import { verticalReadUrl } from './verticalRightsizerApi';
import { adaptHpaPage } from './hpaAdapter';
import { readInsight, isObject } from './insightRead';
import { adaptHpaHistory } from './automationInsightsAdapter';
import type {
  HpaHistoriesResponseWire,
  HpaHistoryResponseWire,
  HpaHistoryView,
  HpaHistoryWindow,
  InsightReadResult,
} from '../types/automationInsights';
import type {
  VerticalEngineResponseWire,
  VerticalRefusalWire,
  VerticalWorkloadRefWire,
} from '../types/verticalRightsizer';
import type {
  HpaApplyRequest,
  HpaApplyResponseWire,
  HpaApplyResult,
  HpaReadResult,
  HpaRefusalWire,
} from '../types/hpaAutomation';

const BASE = '/api/eks/automation/hpa';

const seg = (value: string): string => encodeURIComponent(value);

/** The apply URL. `{name}` is the WORKLOAD name — the backend finds the HPA that targets it. */
export const hpaApplyUrl = (
  cluster: string,
  ref: VerticalWorkloadRefWire,
  accountId: string,
  region: string,
): string =>
  `${BASE}/${seg(cluster)}/${seg(ref.namespace)}/${seg(ref.name)}/apply` +
  `?accountId=${seg(accountId)}&region=${seg(region)}`;

/** One HPA's history URL (FEAT-004). `{name}` is the HPA's own name, not the workload's. */
export const hpaHistoryUrl = (
  cluster: string,
  hpa: { namespace: string; name: string },
  accountId: string,
  region: string,
  window: HpaHistoryWindow,
): string =>
  `${BASE}/${seg(cluster)}/${seg(hpa.namespace)}/${seg(hpa.name)}/history` +
  `?accountId=${seg(accountId)}&region=${seg(region)}&window=${window}`;

const readJson = async <T>(response: Response): Promise<T | null> => {
  try {
    const text = await response.text();
    if (!text || text.trim() === '') return null;
    return JSON.parse(text) as T;
  } catch {
    return null;
  }
};

const isHpaHistoryWire = (body: unknown): body is HpaHistoryResponseWire =>
  isObject(body) && (Array.isArray(body.series) || isObject(body.unavailable));

/**
 * One HPA's replicas, bounds and CPU against its target over a window, from kube-state-metrics in
 * the cluster, with the time it spent at its maximum and every change of its replica count (60 s
 * samples). Read-only; a source failure is a `200` with `unavailable` set.
 */
export const fetchHpaHistory = (
  cluster: string,
  hpa: { namespace: string; name: string },
  accountId: string,
  region: string,
  window: HpaHistoryWindow,
  signal?: AbortSignal,
): Promise<InsightReadResult<HpaHistoryView>> =>
  readInsight(hpaHistoryUrl(cluster, hpa, accountId, region, window), {
    isWire: isHpaHistoryWire,
    adapt: adaptHpaHistory,
    what: 'The HPA history',
    signal,
  });

/** The HPA list's one history read: every HPA named, `namespace/name`, comma-separated. */
export const hpaHistoriesUrl = (
  cluster: string,
  hpas: ReadonlyArray<{ namespace: string; name: string }>,
  accountId: string,
  region: string,
  window: HpaHistoryWindow,
): string =>
  `${BASE}/${seg(cluster)}/history?accountId=${seg(accountId)}&region=${seg(region)}&window=${window}` +
  `&hpas=${seg(hpas.map((hpa) => `${hpa.namespace}/${hpa.name}`).join(','))}`;

const isHpaHistoriesWire = (body: unknown): body is HpaHistoriesResponseWire =>
  isObject(body) && Array.isArray(body.histories);

/**
 * Several HPAs' histories with one read, for the HPA list: one cluster connection on the server
 * rather than one per HPA. Each HPA's answer is what {@link fetchHpaHistory} gives for it: its
 * history (its own `unavailable` included), the refusal for an HPA the cluster doesn't have, or the
 * read's own failure, the same for every HPA.
 */
export async function fetchHpaHistories(
  cluster: string,
  hpas: ReadonlyArray<{ namespace: string; name: string }>,
  accountId: string,
  region: string,
  window: HpaHistoryWindow,
  signal?: AbortSignal,
): Promise<Record<string, InsightReadResult<HpaHistoryView>>> {
  const read = await readInsight(hpaHistoriesUrl(cluster, hpas, accountId, region, window), {
    isWire: isHpaHistoriesWire,
    adapt: (wire: HpaHistoriesResponseWire) => wire,
    what: 'The HPA history',
    signal,
  });
  const results: Record<string, InsightReadResult<HpaHistoryView>> = {};
  for (const hpa of hpas) {
    const id = `${hpa.namespace}/${hpa.name}`;
    if (read.kind !== 'ok') {
      results[id] = read;
      continue;
    }
    const entry = (read.view.histories ?? []).find((h) => h?.namespace === hpa.namespace && h?.name === hpa.name);
    if (entry && isHpaHistoryWire(entry)) {
      results[id] = { kind: 'ok', view: adaptHpaHistory(entry) };
    } else if ((read.view.missing ?? []).some((m) => m?.namespace === hpa.namespace && m?.name === hpa.name)) {
      // The one-HPA read's 404 for an HPA neither Prometheus nor the cluster knows.
      results[id] = {
        kind: 'refused',
        code: parseCode('K8S_INVALID_REQUEST')!,
        message: `This cluster has no HPA ${id}, and Prometheus holds no history for one.`,
        field: 'name',
      };
    } else {
      results[id] = { kind: 'error', message: `The HPA history answered without ${id}.` };
    }
  }
  return results;
}

const refusalMessage = (body: HpaRefusalWire | null, fallback: string): string => {
  const message = body?.message;
  return message && message.trim() !== '' ? message : fallback;
};

/**
 * Reads one cluster's HPA page. This is the vertical rightsizer read endpoint: a single `GET`
 * carries each workload's HPA decision (`horizontal`), its native-VPA target (`upstreamVpa`) and the
 * engine's gates, so the HPA page needs no endpoint of its own for reads. Read-only.
 */
export const fetchHpaPage = async (
  cluster: string,
  accountId: string,
  region: string,
): Promise<HpaReadResult> => {
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
    return { kind: 'ok', view: adaptHpaPage(body) };
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
  return { kind: 'error', message: refusalMessage(refusal, `The engine returned HTTP ${response.status}.`) };
};

/**
 * Submits one HPA tune (min / max / target). The direct execution path completes in the request and
 * answers with `applied` echoing the bounds it set; a dry run never writes. Only a reply whose
 * state is `ACCEPTED` with an `applied` block may be read as a real change — nothing here infers one.
 */
export const applyHpaRecommendation = async (
  cluster: string,
  ref: VerticalWorkloadRefWire,
  accountId: string,
  region: string,
  request: HpaApplyRequest,
): Promise<HpaApplyResult> => {
  let response: Response;
  try {
    response = await apiClient.fetch(hpaApplyUrl(cluster, ref, accountId, region), {
      method: 'POST',
      body: JSON.stringify(request),
    });
  } catch (e) {
    return { kind: 'error', message: e instanceof Error ? e.message : 'The request could not be sent.' };
  }

  if (response.status === 404) {
    const notFound = await readJson<HpaRefusalWire>(response);
    const code = parseCode(notFound?.code);
    if (code) {
      return {
        kind: 'cluster-not-registered',
        message: refusalMessage(notFound, 'This cluster is not registered with XamOps in this environment.'),
      };
    }
    return { kind: 'writes-not-deployed' };
  }

  if (response.ok) {
    const body = await readJson<HpaApplyResponseWire>(response);
    return {
      kind: 'submitted',
      correlationId: body?.correlationId ?? null,
      state: body?.state ?? null,
      dryRun: body?.dryRun ?? null,
      applied: body?.applied ?? null,
    };
  }

  const refusal = await readJson<HpaRefusalWire>(response);
  const code = parseCode(refusal?.code);
  // 403 caller gates, 400 safety-rule / shape refusals, 409 another apply still running.
  if (code && (response.status === 403 || response.status === 400 || response.status === 409)) {
    const detail = typeof refusal?.detail === 'string' && refusal.detail.trim() !== '' ? refusal.detail.trim() : null;
    return { kind: 'refused', code, message: refusalMessage(refusal, 'The apply was refused.'), detail };
  }
  return {
    kind: 'error',
    message: refusalMessage(refusal, `The HPA apply endpoint returned HTTP ${response.status}.`),
  };
};
