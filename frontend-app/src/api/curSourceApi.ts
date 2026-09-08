import type {
  AwsBillingSource,
  CurReconciliation,
  CurSource,
  CurSourceRegistration,
} from '../types/curSource';

const BASE = '/api/xamops/admin/cur-sources';

/**
 * Reads an error body as text.
 *
 * The backend returns {"error": "...", "detail": "..."} for the failures an
 * operator can act on — a source that has not validated, an account with no rows
 * in the chosen report — so surfacing that text matters more than the status code.
 */
const errorFrom = async (response: Response): Promise<string> => {
  try {
    const body = await response.json();
    return [body.error, body.detail].filter(Boolean).join(' — ') || `HTTP ${response.status}`;
  } catch {
    return `HTTP ${response.status}`;
  }
};

const request = async <T>(url: string, init?: RequestInit): Promise<T> => {
  const response = await fetch(url, {
    credentials: 'include',
    headers: { Accept: 'application/json', ...(init?.body ? { 'Content-Type': 'application/json' } : {}) },
    ...init,
  });
  if (!response.ok) {
    throw new Error(await errorFrom(response));
  }
  return response.json() as Promise<T>;
};

export const listCurSources = (): Promise<CurSource[]> => request<CurSource[]>(BASE);

/** One payer report as the onboarding picker needs it — no operational detail. */
export interface AvailableCurSource {
  id: number;
  name: string;
  ownerAccountId: string;
}

/**
 * Payer reports an account can be attached to.
 *
 * Deliberately not {@link listCurSources}: that endpoint is super-admin only, because
 * registering and assigning reports changes real billing state. Someone connecting an
 * account still has to see which payers exist in order to choose one, so this reads from
 * the non-admin lookup instead.
 */
export const listAvailableCurSources = (): Promise<AvailableCurSource[]> =>
  request<AvailableCurSource[]>('/api/xamops/cur-sources/available');

/** Registers a report, then validates and provisions its Athena table in one call. */
export const registerCurSource = (payload: CurSourceRegistration): Promise<CurSource> =>
  request<CurSource>(BASE, { method: 'POST', body: JSON.stringify(payload) });

/** Re-reads the manifest and rebuilds the table — how a FAILED source is retried. */
export const validateCurSource = (id: number): Promise<CurSource> =>
  request<CurSource>(`${BASE}/${id}/validate`, { method: 'POST' });

export const listSourceAccounts = (id: number): Promise<string[]> =>
  request<string[]>(`${BASE}/${id}/accounts`);

/**
 * Compares CUR and Cost Explorer for one closed month.
 *
 * Run before switching an account over: a user changing a date filter can move
 * between the two sources, so they have to agree.
 */
export const reconcileAccount = (
  awsAccountId: string,
  period?: string,
  curSourceId?: number,
): Promise<CurReconciliation> => {
  const params = new URLSearchParams();
  if (period) params.set('period', period);
  if (curSourceId != null) params.set('curSourceId', String(curSourceId));
  const query = params.toString();
  return request<CurReconciliation>(`${BASE}/reconcile/${awsAccountId}${query ? `?${query}` : ''}`);
};

/**
 * Points an account at a CUR source, or back at Cost Explorer.
 *
 * The server refuses when the account has no rows in the chosen report — the
 * common mistake once more than one payer account is connected. `skipPresenceCheck`
 * exists only for an account whose first delivery has not landed yet.
 */
export const assignBillingSource = (
  awsAccountId: string,
  billingSource: AwsBillingSource,
  curSourceId?: number,
  skipPresenceCheck = false,
): Promise<Record<string, unknown>> => {
  const params = new URLSearchParams({ billingSource });
  if (curSourceId != null) params.set('curSourceId', String(curSourceId));
  if (skipPresenceCheck) params.set('skipPresenceCheck', 'true');
  return request(`${BASE}/assign/${awsAccountId}?${params.toString()}`, { method: 'PUT' });
};
