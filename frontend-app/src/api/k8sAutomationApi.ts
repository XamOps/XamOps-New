/**
 * CloudK8s automation API: capabilities and the Go automation agent's endpoints
 * (`/api/eks/autoscaling/**`), typed, with one error type (docs/cloudk8s/ui-revamp-design.md §11.4, §12.2).
 *
 * Calls go through `apiFetch` directly and read the JSON body, rather than the
 * `"HTTP <status>: <body>"` message the convenience wrapper throws, so a refusal's
 * `code`, `field` and `detail` survive (§12.2).
 */
import { apiFetch } from '../utils/apiClient';
import {
  type AutomationDashboardDto,
  type AutomationEvictorCandidate,
  type AutomationEvictorPolicy,
  type AutomationNodeFleet,
  type AutomationNodeOptimizerPolicy,
  type AutomationNodeTemplate,
  type AutomationRebalanceStatus,
  type AutomationRebalancerConfig,
  type AutomationSafetyGuards,
  type AutomationSaveResult,
  type AutomationScaleOutPolicy,
  type AutomationScope,
  type CapabilitiesDto,
  CODE_PREFIX,
  stripCodePrefix,
} from '../types/k8sAutomation';

/** Server messages are shown as plain text, capped so a runaway body can't flood the UI. */
export const MAX_MESSAGE_LENGTH = 300;

/** A failed CloudK8s request: HTTP status plus the refusal body's fields when there was one. */
export class K8sApiError extends Error {
  readonly status: number;
  /** The wire code, e.g. `K8S_READ_ONLY_CONNECTION`, or null when the body had none. */
  readonly code: string | null;
  /** The code without the prefix, e.g. `READ_ONLY_CONNECTION`. */
  readonly reason: string | null;
  readonly field: string | null;
  readonly detail: string | null;

  constructor(init: { status: number; code?: string | null; message?: string | null; field?: string | null; detail?: string | null }) {
    super(capMessage(init.message) ?? fallbackMessage(init.status));
    this.name = 'K8sApiError';
    this.status = init.status;
    this.code = init.code ?? null;
    this.reason = init.code ? stripCodePrefix(init.code) : null;
    this.field = init.field ?? null;
    this.detail = init.detail ?? null;
  }
}

function capMessage(message: string | null | undefined): string | null {
  if (typeof message !== 'string') return null;
  // Plain text only: collapse whitespace so a multi-line body can't break the layout.
  const text = message.replace(/\s+/g, ' ').trim();
  if (!text) return null;
  return text.length > MAX_MESSAGE_LENGTH ? `${text.slice(0, MAX_MESSAGE_LENGTH - 1)}…` : text;
}

function fallbackMessage(status: number): string {
  if (status === 0) return "Couldn't reach XamOps. Check your connection and try again.";
  if (status >= 500) return 'XamOps had a problem with this request. Try again.';
  return `The request failed (HTTP ${status}).`;
}

/**
 * Sends a request and returns its JSON body, or throws {@link K8sApiError}.
 * A 2xx with an empty body resolves to `null`.
 */
export async function k8sRequest<T>(url: string, init: RequestInit & { timeoutMs?: number } = {}): Promise<T> {
  let response: Response;
  try {
    response = await apiFetch(url, init);
  } catch (error) {
    // A caller's own abort (unmount, account change) is not a failure to report.
    if (error instanceof DOMException && error.name === 'AbortError') throw error;
    const timedOut = error instanceof Error && /timed out/i.test(error.message);
    throw new K8sApiError({
      status: 0,
      message: timedOut ? 'The request timed out. Try again.' : null,
    });
  }

  const text = await response.text().catch(() => '');
  let body: unknown = null;
  if (text.trim()) {
    try {
      body = JSON.parse(text);
    } catch {
      body = null;
    }
  }

  if (!response.ok) {
    const refusal = body && typeof body === 'object' ? (body as Record<string, unknown>) : null;
    const code = typeof refusal?.code === 'string' ? refusal.code : null;
    throw new K8sApiError({
      status: response.status,
      code,
      // Without a code the body is not a refusal (an HTML error page, a proxy error), so its
      // message is not shown.
      message: code && typeof refusal?.message === 'string' ? refusal.message : null,
      field: typeof refusal?.field === 'string' ? refusal.field : null,
      detail: typeof refusal?.detail === 'string' ? refusal.detail : null,
    });
  }
  return body as T;
}

export function isK8sApiError(error: unknown): error is K8sApiError {
  return error instanceof K8sApiError;
}

/** True when the error's code is one of ours (the response was a deliberate refusal). */
export function hasK8sCode(error: unknown): error is K8sApiError & { code: string } {
  return isK8sApiError(error) && typeof error.code === 'string' && error.code.startsWith(CODE_PREFIX);
}

const json = (body: unknown): RequestInit => ({ body: JSON.stringify(body) });

const enc = encodeURIComponent;

// ── Capabilities ─────────────────────────────────────────────────────────────

export function getCapabilities(accountId: string, init?: RequestInit): Promise<CapabilitiesDto> {
  return k8sRequest<CapabilitiesDto>(`/api/xamops/k8s/capabilities?accountId=${enc(accountId)}`, init);
}

// ── Node automation (/api/eks/autoscaling/**) ────────────────────────────────

const base = (cluster: string) => `/api/eks/autoscaling/${enc(cluster)}`;
const query = (accountId: string, region?: string) =>
  `accountId=${enc(accountId)}${region ? `&region=${enc(region)}` : ''}`;

export interface AutomationTarget {
  cluster: string;
  accountId: string;
  region?: string;
}

export function getAutomationDashboard(t: AutomationTarget, init?: RequestInit): Promise<AutomationDashboardDto> {
  return k8sRequest(`${base(t.cluster)}?${query(t.accountId, t.region)}`, init);
}

export function getEvictorCandidates(t: AutomationTarget, init?: RequestInit): Promise<AutomationEvictorCandidate[]> {
  return k8sRequest(`${base(t.cluster)}/evictor-candidates?${query(t.accountId)}`, init);
}

export function getNodeFleet(t: AutomationTarget, init?: RequestInit): Promise<AutomationNodeFleet> {
  return k8sRequest(`${base(t.cluster)}/node-fleet?${query(t.accountId)}`, init);
}

export function getRebalanceStatus(t: AutomationTarget, init?: RequestInit): Promise<AutomationRebalanceStatus> {
  return k8sRequest(`${base(t.cluster)}/rebalance-status?${query(t.accountId)}`, init);
}

export function setAutomationEnabled(t: AutomationTarget, enabled: boolean): Promise<AutomationSaveResult> {
  return k8sRequest(`${base(t.cluster)}/enabled?${query(t.accountId)}`, { method: 'PUT', ...json({ enabled }) });
}

export function setAutomationScope(t: AutomationTarget, mode: AutomationScope): Promise<AutomationSaveResult> {
  return k8sRequest(`${base(t.cluster)}/mode?${query(t.accountId)}`, { method: 'PUT', ...json({ mode }) });
}

/** Each section save replaces the whole section: send the current value with the draft applied (§6.5). */
export function saveScaleOutPolicy(t: AutomationTarget, body: AutomationScaleOutPolicy): Promise<AutomationSaveResult> {
  return k8sRequest(`${base(t.cluster)}/scale-out-policy?${query(t.accountId)}`, { method: 'PUT', ...json(body) });
}

export function saveEvictorPolicy(t: AutomationTarget, body: AutomationEvictorPolicy): Promise<AutomationSaveResult> {
  return k8sRequest(`${base(t.cluster)}/evictor-policy?${query(t.accountId)}`, { method: 'PUT', ...json(body) });
}

export function saveSafetyGuards(t: AutomationTarget, body: AutomationSafetyGuards): Promise<AutomationSaveResult> {
  return k8sRequest(`${base(t.cluster)}/safety-guards?${query(t.accountId)}`, { method: 'PUT', ...json(body) });
}

export function saveNodeOptimizerPolicy(t: AutomationTarget, body: AutomationNodeOptimizerPolicy): Promise<AutomationSaveResult> {
  return k8sRequest(`${base(t.cluster)}/node-optimizer?${query(t.accountId)}`, { method: 'PUT', ...json(body) });
}

export function saveRebalancerConfig(t: AutomationTarget, body: AutomationRebalancerConfig): Promise<AutomationSaveResult> {
  return k8sRequest(`${base(t.cluster)}/rebalancer?${query(t.accountId)}`, { method: 'PUT', ...json(body) });
}

export function saveNodeTemplate(t: AutomationTarget, templateId: string, body: AutomationNodeTemplate): Promise<AutomationSaveResult> {
  return k8sRequest(`${base(t.cluster)}/node-templates/${enc(templateId)}?${query(t.accountId)}`, {
    method: 'PUT',
    ...json(body),
  });
}

export function deleteNodeTemplate(t: AutomationTarget, templateId: string): Promise<AutomationSaveResult> {
  return k8sRequest(`${base(t.cluster)}/node-templates/${enc(templateId)}?${query(t.accountId)}`, { method: 'DELETE' });
}

export function triggerRebalance(t: AutomationTarget): Promise<AutomationSaveResult> {
  return k8sRequest(`${base(t.cluster)}/rebalance?${query(t.accountId)}`, { method: 'POST' });
}
