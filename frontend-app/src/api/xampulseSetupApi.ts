import { xpGet, xpPost, xpRequest } from './xampulseApi';
import type {
  XpAuditEntry, XpBucketCandidate, XpChecklistCheck, XpDemoStatus, XpDisconnectRequest, XpDisconnectResponse,
  XpInstanceCandidate, XpLogSource, XpPage, XpSetupOverview, XpSetupPlanRequest, XpSetupPlanResponse,
  XpStackLink, XpStackStatus, XpVerifyResponse, XpCollectionUpdate, XpRegionPlanRequest,
} from '../types/xampulse';

/** Setup, onboarding and storage endpoints (contract 2.9). Admin-only calls are enforced server-side. */
export const xampulseSetupApi = {
  overview: (accountId: number) => xpGet<XpSetupOverview>(`/accounts/${accountId}/setup`),
  instances: (accountId: number, q: { q?: string; state?: string; page?: number; size?: number } = {}) =>
    xpGet<XpPage<XpInstanceCandidate>>(`/accounts/${accountId}/instances`, q),
  buckets: (accountId: number) => xpGet<XpBucketCandidate[]>(`/accounts/${accountId}/buckets`),
  plan: (accountId: number, req: XpSetupPlanRequest) => xpPost<XpSetupPlanResponse>(`/accounts/${accountId}/setup-plan`, req),
  stackStatus: (accountId: number, region?: string) => xpGet<XpStackStatus[]>(`/accounts/${accountId}/stack-status`, { region }),
  verify: (accountId: number) => xpPost<XpVerifyResponse>(`/accounts/${accountId}/verify`, {}),
  regionPlan: (accountId: number, region: string, req: XpRegionPlanRequest) =>
    xpPost<XpStackLink>(`/accounts/${accountId}/regions/${encodeURIComponent(region)}/plan`, req),
  setCollection: (accountId: number, instanceId: string, u: XpCollectionUpdate) =>
    xpRequest<XpInstanceCandidate>('PUT', `/accounts/${accountId}/instances/${encodeURIComponent(instanceId)}/collection`, { body: u }),
  disconnect: (accountId: number, req: XpDisconnectRequest) => xpPost<XpDisconnectResponse>(`/accounts/${accountId}/disconnect`, req),
  sources: (accountId: number) => xpGet<XpLogSource[]>(`/accounts/${accountId}/sources`),
  saveSources: (accountId: number, s: XpLogSource[]) => xpRequest<XpLogSource[]>('PUT', `/accounts/${accountId}/sources`, { body: s }),
  audit: (accountId: number, page = 0) => xpGet<XpPage<XpAuditEntry>>('/audit', { accountId, page }),
  seedDemo: (accountId: number) => xpPost<XpDemoStatus>(`/accounts/${accountId}/demo/seed`, {}),
  removeDemo: (accountId: number) => xpRequest<XpDemoStatus>('DELETE', `/accounts/${accountId}/demo`),
};

export const CHECKLIST_POLL_MS = 3000;

/** Checklist as SSE with a polling fallback (the plan: SSE first, 3 s polling when SSE fails). */
export function watchChecklist(
  accountId: number,
  onChecks: (checks: XpChecklistCheck[]) => void,
  onError: (message: string) => void,
  useSse: boolean,
): () => void {
  let closed = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let es: EventSource | undefined;
  const poll = async () => {
    if (closed) return;
    try {
      const r = await xampulseSetupApi.verify(accountId);
      if (!closed) onChecks(r.checks);
    } catch (e) {
      if (!closed) onError(e instanceof Error ? e.message : 'Verification failed.');
    }
    if (!closed) timer = setTimeout(poll, CHECKLIST_POLL_MS);
  };
  if (useSse && typeof EventSource !== 'undefined') {
    const byId = new Map<string, XpChecklistCheck>();
    es = new EventSource(`/api/xamops/xampulse/accounts/${accountId}/checklist/stream`, { withCredentials: true });
    let finished = false;
    const onCheck = (ev: MessageEvent<string>) => {
      try {
        const c = JSON.parse(ev.data) as XpChecklistCheck;
        byId.set(`${c.id}:${c.region ?? ''}`, c);
        onChecks(Array.from(byId.values()));
      } catch {
        /* ignore malformed frame */
      }
    };
    // The server sends NAMED events ("check" per changed check, "done" when connected); onmessage alone would never fire.
    es.addEventListener('check', onCheck as EventListener);
    es.onmessage = onCheck;
    es.addEventListener('done', () => {
      finished = true;
      es?.close();
      es = undefined;
    });
    es.onerror = () => {
      es?.close();
      es = undefined;
      if (!closed && !finished) void poll();
    };
  } else {
    void poll();
  }
  return () => {
    closed = true;
    if (timer) clearTimeout(timer);
    es?.close();
  };
}
