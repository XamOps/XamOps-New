/**
 * One read of endpoint 1 (`GET /api/eks/automation/vertical/{cluster}`) for the Automation pages
 * that show both what the Workload autoscaler decided and what the HPA and VPA views derive from the
 * same payload (Overview, Policies, and FEAT-003's HPA, VPA and Activity pages).
 *
 * A single `GET` is adapted twice: `adaptEngineResponse` for the vertical view and `adaptHpaPage`
 * for the HPA/VPA view, so a page never reads the cluster twice to show both. The status mapping is
 * `fetchVerticalEngine`'s, duplicated on purpose so that function and its tests stay exactly as they
 * are: a `401` is a lapsed session, a typed `403` is a refusal with its gate code, a `404` is a
 * cluster this environment doesn't know, and anything else is an error with the server's message.
 */

import apiClient from '../utils/apiClient';
import { adaptEngineResponse, parseCode } from './verticalRightsizerAdapter';
import { adaptHpaPage } from './hpaAdapter';
import { verticalReadUrl } from './verticalRightsizerApi';
import type {
  CodeView,
  VerticalEngineResponseWire,
  VerticalEngineView,
  VerticalRefusalWire,
} from '../types/verticalRightsizer';
import type { HpaPageView } from '../types/hpaAutomation';

/** Both views of one endpoint-1 body. */
export interface AutomationReadView {
  vertical: VerticalEngineView;
  hpa: HpaPageView;
}

/** The kinds `fetchVerticalEngine` answers with, carrying both views on success. */
export type AutomationReadResult =
  | { kind: 'ok'; view: AutomationReadView }
  | { kind: 'cluster-not-registered'; message: string }
  | { kind: 'refused'; code: CodeView; message: string }
  | { kind: 'unauthenticated' }
  | { kind: 'error'; message: string };

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

/** Engine status, gate reasons, recommendations and HPA decisions for one cluster. Read-only. */
export const fetchAutomationRead = async (
  cluster: string,
  accountId: string,
  region: string,
): Promise<AutomationReadResult> => {
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
    return { kind: 'ok', view: { vertical: adaptEngineResponse(body), hpa: adaptHpaPage(body) } };
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
