/**
 * useBillOpsAdminTrials Hook
 *
 * Backs the Trial Management section of the BillOps admin user manager:
 * - GET  /api/billops/admin/trials?includeConverted=true  (list, converted tenants included)
 * - POST /api/billops/admin/trials/{clientId}/extend      (body values must be STRINGS)
 * - POST /api/billops/admin/trials/{clientId}/convert
 *
 * Deliberately NOT cached in localStorage, unlike useBillOpsAdminCredits and
 * useBillOpsAdminInvoices. A stale trial verdict is the exact failure this screen exists to
 * remove, and the list is a handful of rows, so it is always read fresh.
 *
 * For: ROLE_BILLOPS_ADMIN / ROLE_SUPER_ADMIN
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { apiFetch } from '../utils/apiClient';
import { parseServerDateTime } from '../utils/trialVerdict';
import type {
  TrialAdminRow,
  TrialConvertResult,
  TrialExtendResult,
  TrialMutationResult,
  UseBillOpsAdminTrialsReturn,
} from '../types/billopsAdminTrials';

const LIST_URL = '/api/billops/admin/trials?includeConverted=true';

/**
 * One extractor for every failing response.
 *
 * The body is read once (a Response body can only be consumed once), parsed as JSON when it
 * can be, and `message` preferred over `error` because Spring's default error body and the
 * controller's own `Map.of("error", ...)` use different keys. BillOps answers /api/** 401s
 * through HttpStatusEntryPoint with an EMPTY body, so a per-status fallback is required.
 */
const extractError = async (response: Response): Promise<string> => {
  let text = '';
  try {
    text = await response.text();
  } catch {
    text = '';
  }

  const trimmed = text.trim();
  if (trimmed) {
    try {
      const parsed = JSON.parse(trimmed);
      const message = parsed?.message ?? parsed?.error;
      if (typeof message === 'string' && message.trim()) return message;
    } catch {
      // Not JSON — a plain-text body is still better copy than "HTTP 500".
      if (!trimmed.startsWith('<')) return trimmed;
    }
  }

  switch (response.status) {
    case 401:
      // apiFetch already dispatches session-expired; this is only what the section renders.
      return 'Your session has expired. Sign in again to manage trials.';
    case 403:
      return 'You need BillOps admin rights to manage trials.';
    case 402:
      // The wording TrialEnforcementFilter writes when it does answer with a body.
      return 'TRIAL_EXPIRED: Your free trial has ended. Please contact sales to continue.';
    case 404:
      return 'That tenant no longer exists.';
    default:
      return `HTTP ${response.status}`;
  }
};

/**
 * Blocked tenants first (they are why anyone opened this screen), then the soonest end date,
 * then everything that is not trial-gated. The endpoint has no ORDER BY, so ordering is ours.
 */
const sortRows = (rows: TrialAdminRow[]): TrialAdminRow[] => {
  const rank = (row: TrialAdminRow): number => {
    if (row.planType !== 'TRIAL') return 2;
    return row.trialStatus === 'EXPIRED' ? 0 : 1;
  };

  return [...rows].sort((a, b) => {
    const byRank = rank(a) - rank(b);
    if (byRank !== 0) return byRank;

    const aEnd = parseServerDateTime(a.trialEndAt)?.getTime();
    const bEnd = parseServerDateTime(b.trialEndAt)?.getTime();
    if (aEnd != null && bEnd != null && aEnd !== bEnd) return aEnd - bEnd;
    if (aEnd != null && bEnd == null) return -1;
    if (aEnd == null && bEnd != null) return 1;

    return a.clientId - b.clientId;
  });
};

export const useBillOpsAdminTrials = (): UseBillOpsAdminTrialsReturn => {
  const [rows, setRows] = useState<TrialAdminRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [mutatingClientId, setMutatingClientId] = useState<number | null>(null);
  const [mutationError, setMutationError] = useState<string | null>(null);
  const [lastResult, setLastResult] = useState<TrialMutationResult | null>(null);

  const isMountedRef = useRef(true);
  /** Whether a list call has ever succeeded — i.e. whether `rows` holds anything worth keeping. */
  const hasLoadedRef = useRef(false);

  const refresh = useCallback(async () => {
    if (isMountedRef.current) {
      setLoading(true);
      setError(null);
    }

    try {
      const response = await apiFetch(LIST_URL, { credentials: 'include' });

      if (!response.ok) {
        // No redirect on 401/403 (unlike useBillOpsAdminCredits): a missing trial role on a
        // page the admin legitimately has should disable this section, not the whole page.
        const message = await extractError(response);
        if (isMountedRef.current) {
          setError(message);
          // Only blank the table when there is nothing to preserve. A transient failure on a
          // post-mutation or manual refetch must not take away rows the operator is reading;
          // the error renders above them instead.
          if (!hasLoadedRef.current) setRows([]);
        }
        return;
      }

      const data = await response.json();
      if (isMountedRef.current) {
        setRows(Array.isArray(data) ? sortRows(data) : []);
        hasLoadedRef.current = true;
      }
    } catch (err) {
      console.error('Failed to load trial tenants:', err);
      if (isMountedRef.current) {
        setError(err instanceof Error ? err.message : 'Could not load trial tenants.');
      }
    } finally {
      if (isMountedRef.current) setLoading(false);
    }
  }, []);

  /** Shared body for both mutations: POST, surface the error, refetch on success. */
  const mutate = useCallback(async (
    clientId: number,
    path: string,
    body: string | undefined,
    toResult: (payload: unknown) => TrialMutationResult,
  ): Promise<boolean> => {
    if (isMountedRef.current) {
      setMutatingClientId(clientId);
      setMutationError(null);
      setLastResult(null);
    }

    try {
      const response = await apiFetch(`/api/billops/admin/trials/${clientId}${path}`, {
        method: 'POST',
        credentials: 'include',
        ...(body !== undefined
          ? { headers: { 'Content-Type': 'application/json' }, body }
          : {}),
      });

      if (!response.ok) {
        const message = await extractError(response);
        if (isMountedRef.current) setMutationError(message);
        return false;
      }

      const payload = await response.json().catch(() => null);

      // The refetch comes FIRST so the success result lands on a table that already shows the
      // new dates, and so the success modal cannot be raised while the dialog that submitted
      // the change is still on screen (see the section's success-modal gate).
      await refresh();
      if (isMountedRef.current) setLastResult(toResult(payload));
      return true;
    } catch (err) {
      console.error('Trial mutation failed:', err);
      if (isMountedRef.current) {
        setMutationError(err instanceof Error ? err.message : 'The change could not be saved.');
      }
      return false;
    } finally {
      if (isMountedRef.current) setMutatingClientId(null);
    }
  }, [refresh]);

  const extendTrial = useCallback((clientId: number, days: number) =>
    // `days` goes over the wire as a STRING: the controller deserialises the body into a
    // Map<String, String>, so a JSON number fails to bind.
    mutate(
      clientId,
      '/extend',
      JSON.stringify({ days: String(days) }),
      (payload) => ({ kind: 'extend', ...(payload as TrialExtendResult) }),
    ), [mutate]);

  const convertToPaid = useCallback((clientId: number) =>
    mutate(
      clientId,
      '/convert',
      undefined,
      (payload) => ({ kind: 'convert', ...(payload as TrialConvertResult) }),
    ), [mutate]);

  const clearMutationState = useCallback(() => {
    setMutationError(null);
    setLastResult(null);
  }, []);

  useEffect(() => {
    isMountedRef.current = true;
    refresh();
    return () => {
      isMountedRef.current = false;
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  return {
    rows,
    loading,
    error,
    mutatingClientId,
    mutationError,
    lastResult,
    refresh,
    extendTrial,
    convertToPaid,
    clearMutationState,
  };
};

export default useBillOpsAdminTrials;
