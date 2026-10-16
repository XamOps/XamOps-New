import { useCallback, useEffect, useState } from 'react';
import apiClient from '../utils/apiClient';
import type { UserActivitySummary } from '../types/userActivity';

const BASE = '/api/xamops/billops-admin';

/**
 * Extracts a readable message from an apiClient error.
 *
 * `apiFetchJson` throws `Error("HTTP 403: <body>")`, so the backend's sentence is buried in
 * the message string. Matches the helper in `useDspmAdmin`.
 */
function readError(err: unknown): string {
  const raw = err instanceof Error ? err.message : String(err);
  const brace = raw.indexOf('{');
  if (brace >= 0) {
    try {
      const parsed = JSON.parse(raw.slice(brace));
      return parsed.message || parsed.error || parsed.detail || raw;
    } catch {
      /* fall through to raw text */
    }
  }
  return raw;
}

/**
 * Loads the activity summary for one user.
 *
 * Uncached, and fetched on open rather than with the client list. That list is served from a
 * Redis-warmed DTO built by `BillOpsClientsCacheWarmingService`, so reading activity from it
 * would show timestamps minutes out of date in the one window whose purpose is being current.
 *
 * Passing `userId = null` keeps the hook idle, so the window can mount closed without firing
 * a request for whichever row happens to be first.
 */
export function useUserActivity(userId: number | null, days = 90) {
  const [data, setData] = useState<UserActivitySummary | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (userId == null) return;
    setLoading(true);
    setError(null);
    try {
      setData(await apiClient.get<UserActivitySummary>(
        `${BASE}/users/${userId}/activity?days=${days}`,
      ));
    } catch (err) {
      setError(readError(err));
      setData(null);
    } finally {
      setLoading(false);
    }
  }, [userId, days]);

  useEffect(() => {
    if (userId == null) {
      // Clear on close so reopening on a different user cannot flash the previous one's
      // figures while the new request is in flight.
      setData(null);
      setError(null);
      return;
    }
    void load();
  }, [userId, days, load]);

  return { data, loading, error, reload: load };
}
