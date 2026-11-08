import { useCallback, useEffect, useState } from 'react';
import apiClient from '../utils/apiClient';
import type { DspmError, DspmStatus } from '../types/dspm';

const BASE = '/api/dspm';

/** 10 first, as the console's tables have it. */
export const DSPM_PAGE_SIZES = [10, 25, 50, 100] as const;

/**
 * The three asset surfaces, matching the backend's `DspmAssetQuery.Surface` slugs.
 *
 * Each maps server-side to a `resource_type` scope — cloud to `DATASTORE` (which covers self-hosted
 * datastores too, as the vendor console's Cloud screen does), saas to `SAAS_PRINCIPAL`, endpoint to
 * `ENDPOINT_HOST`. Scoping by `resource_type` rather than `category` is load-bearing: `category` is a
 * single-valued tab selector, and an unrecognised value there returns an empty result set on a 200.
 */
export type DspmSurface = 'cloud' | 'saas' | 'endpoint';

/**
 * Parses a DSPM error body.
 *
 * The backend answers every failure with `{code, message}` where `code` is a stable enum name. Branching
 * on the code is the contract; the message is prose and will be reworded. `apiFetchJson` throws
 * `Error("HTTP 409: <body>")`, so the JSON has to be dug back out of the message string.
 */
export function parseDspmError(err: unknown): DspmError | null {
  const raw = err instanceof Error ? err.message : String(err);
  const brace = raw.indexOf('{');
  if (brace < 0) return null;
  try {
    const parsed = JSON.parse(raw.slice(brace));
    return parsed?.code ? (parsed as DspmError) : null;
  } catch {
    return null;
  }
}

/** Whether `apiFetchJson` rejected with this HTTP status (its message starts "HTTP <status>:"). */
const hasHttpStatus = (err: unknown, status: number) =>
  err instanceof Error && err.message.startsWith(`HTTP ${status}:`);

/**
 * Connection state for the DSPM surface.
 *
 * Fetched before anything else and never cached. Every tab needs to know whether reads will work at all,
 * and the answer changes the moment an operator fixes a mapping — a customer refreshing after being told
 * "it's sorted" should see it immediately rather than wait out a TTL.
 */
export function useDspmStatus() {
  const [status, setStatus] = useState<DspmStatus | null>(null);
  const [loading, setLoading] = useState(true);

  const loadStatus = useCallback(async () => {
    setLoading(true);
    try {
      setStatus(await apiClient.get<DspmStatus>(`${BASE}/status`));
    } catch (err) {
      // A user whose XamLens tab grant is missing gets 403 TAB_ACCESS_DENIED from every DSPM endpoint.
      // That is an answer, not an outage, so it becomes its own not-ready state.
      const parsed = parseDspmError(err);
      if (parsed?.code === 'TAB_ACCESS_DENIED' && hasHttpStatus(err, 403)) {
        setStatus({
          ready: false, entitled: false, configured: false,
          reason: 'TAB_ACCESS_DENIED',
          message: parsed.message,
          projectName: null, lastVerifiedAt: null,
        });
        return;
      }
      // The status endpoint is designed not to fail, so a rejection here means the request itself did
      // not land. Reported as an unreachable upstream rather than left null, which the UI would
      // otherwise render as a permanent spinner.
      setStatus({
        ready: false, entitled: false, configured: false,
        reason: 'UPSTREAM_UNAVAILABLE',
        message: 'Could not reach the data security service. Please try again shortly.',
        projectName: null, lastVerifiedAt: null,
      });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void loadStatus(); }, [loadStatus]);

  return { status, statusLoading: loading, reloadStatus: loadStatus };
}
