/**
 * useXamOpsPageRules
 *
 * Backs the XamOps Page Visibility section of the BillOps admin User Manager:
 * - GET    /api/billops/admin/xamops-page-rules
 * - PUT    /api/billops/admin/xamops-page-rules/{key}                    { mode: 'HIDE' | 'GRANT' }
 * - DELETE /api/billops/admin/xamops-page-rules/{key}
 * - POST   /api/billops/admin/xamops-page-rules/{key}/exceptions         { userId }
 * - DELETE /api/billops/admin/xamops-page-rules/{key}/exceptions/{userId}
 *
 * Always read fresh, never cached: the list is a few rows and a stale view of who can see what is
 * the one thing this screen must not show.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { apiFetch } from '../utils/apiClient';
import type {
  UseXamOpsPageRulesResult,
  XamPageRule,
  XamPageRuleMode,
  XamPageRulesListResponse,
} from '../types/xamopsPageRules';

const BASE_URL = '/api/billops/admin/xamops-page-rules';

const ruleUrl = (tabKey: string) => `${BASE_URL}/${encodeURIComponent(tabKey)}`;

/** Prefers the server's `message`; BillOps answers 401/403 on /api/** with an empty body. */
const extractError = async (response: Response): Promise<string> => {
  let text = '';
  try {
    text = (await response.text()).trim();
  } catch {
    text = '';
  }
  if (text) {
    try {
      const parsed = JSON.parse(text);
      const message = parsed?.message ?? parsed?.error;
      if (typeof message === 'string' && message.trim()) return message;
    } catch {
      if (!text.startsWith('<')) return text;
    }
  }
  switch (response.status) {
    case 401: return 'Your session has expired. Sign in again to manage page visibility.';
    case 403: return 'You need BillOps admin rights to manage XamOps page visibility.';
    case 404: return 'That rule no longer exists. Refresh to see the current state.';
    case 409: return 'This page was changed by someone else just now. Refresh and try again.';
    default:  return `HTTP ${response.status}`;
  }
};

const toMap = (rules: XamPageRule[]): Record<string, XamPageRule> =>
  Object.fromEntries(rules.map(r => [r.tabKey, r]));

export const useXamOpsPageRules = (): UseXamOpsPageRulesResult => {
  const [rules, setRules] = useState<Record<string, XamPageRule>>({});
  const [canEdit, setCanEdit] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [savingKey, setSavingKey] = useState<string | null>(null);
  const [mutationError, setMutationError] = useState<string | null>(null);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const response = await apiFetch(BASE_URL, { credentials: 'include' });
      if (!response.ok) {
        const message = await extractError(response);
        if (mountedRef.current) setError(message);
        return;
      }
      const data = (await response.json()) as XamPageRulesListResponse;
      if (!mountedRef.current) return;
      setRules(toMap(Array.isArray(data?.rules) ? data.rules : []));
      setCanEdit(Boolean(data?.canEdit));
      setError(null);
    } catch (e) {
      if (mountedRef.current) setError(e instanceof Error ? e.message : 'Could not load page visibility rules.');
    } finally {
      if (mountedRef.current) setLoading(false);
    }
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);

  /**
   * Runs one write for `tabKey`. A response body carrying the rule replaces it locally; an empty
   * body (DELETE of a rule) removes it.
   */
  const mutate = useCallback(async (tabKey: string, url: string, init: RequestInit): Promise<boolean> => {
    setSavingKey(tabKey);
    setMutationError(null);
    try {
      const response = await apiFetch(url, { credentials: 'include', ...init });
      if (!response.ok) {
        const message = await extractError(response);
        if (mountedRef.current) setMutationError(message);
        return false;
      }
      const text = await response.text();
      const rule = text ? (JSON.parse(text) as XamPageRule) : null;
      if (mountedRef.current) {
        setRules(prev => {
          const next = { ...prev };
          if (rule) next[rule.tabKey] = rule;
          else delete next[tabKey];
          return next;
        });
      }
      return true;
    } catch (e) {
      if (mountedRef.current) setMutationError(e instanceof Error ? e.message : 'Change not saved.');
      return false;
    } finally {
      if (mountedRef.current) setSavingKey(null);
    }
  }, []);

  const setMode = useCallback((tabKey: string, mode: XamPageRuleMode | null) =>
    mode === null
      ? mutate(tabKey, ruleUrl(tabKey), { method: 'DELETE' })
      : mutate(tabKey, ruleUrl(tabKey), { method: 'PUT', body: JSON.stringify({ mode }) }),
  [mutate]);

  const addException = useCallback((tabKey: string, userId: number) =>
    mutate(tabKey, `${ruleUrl(tabKey)}/exceptions`, { method: 'POST', body: JSON.stringify({ userId }) }),
  [mutate]);

  const removeException = useCallback((tabKey: string, userId: number) =>
    mutate(tabKey, `${ruleUrl(tabKey)}/exceptions/${encodeURIComponent(String(userId))}`, { method: 'DELETE' }),
  [mutate]);

  const clearMutationError = useCallback(() => setMutationError(null), []);

  return {
    rules, canEdit, loading, error, savingKey, mutationError,
    refresh, setMode, addException, removeException, clearMutationError,
  };
};
