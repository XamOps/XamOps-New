/**
 * The account's cluster list for the cluster switcher and the fleet view (Phase A design §3.3): the
 * same read `CloudK8sPage` uses, cached per-identity by account only (region and cluster are
 * irrelevant to "every cluster on this account"), and filtered to links the page itself would accept.
 *
 * The hook keeps explicit states and never serves a cached empty list, so a prior empty or failed
 * read can't masquerade as "no clusters": an empty body is reported but not cached, and a second
 * mount refetches. Switching account clears the list to `loading` immediately, before any cache read
 * or fetch, so one account's clusters are never shown under another.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import apiClient from '../utils/apiClient';
import { createK8sCache } from '../utils/k8sCache';
import { classifyApiError, type ApiErrorKind } from '../utils/k8sApiError';
import { isSwitchableCluster } from '../utils/k8sClusterLink';
import type { EKSCluster } from '../pages/CloudK8sPage';

export type K8sClustersStatus = 'loading' | 'ready' | 'error';

/** The fetch outcome, in the same shape for a bare array or a `{clusters}` object. */
interface ClusterListResult {
  clusters: EKSCluster[];
  complete: boolean | null;
  failedRegions: string[];
}

export interface UseK8sClusters {
  status: K8sClustersStatus;
  clusters: EKSCluster[];
  /** Whether the fleet scan covered every region. Always null this task (the backend doesn't report it yet). */
  complete: boolean | null;
  /** Regions the scan failed to read. Always [] this task. */
  failedRegions: string[];
  /** Why the last read failed, or the derived 'incomplete' when a ready read returned nothing from a partial scan; null when fine. */
  errorKind: ApiErrorKind | 'incomplete' | null;
  /** Whether a forced rescan is in flight over an already-rendered list. */
  isRefreshing: boolean;
  /** Re-reads the list without forcing the backend to rescan (clears the per-identity cache first). */
  reload: () => void;
  /** Re-reads the list, forcing the backend to rescan (forceRefresh=true). */
  rescan: () => void;
}

const MAX_CLUSTERS = 200;
const clusterListCache = createK8sCache<ClusterListResult>('k8s-clusters');

export function sanitizeClusters(data: unknown): EKSCluster[] | null {
  if (!Array.isArray(data)) return null;
  const seen = new Set<string>();
  const out: EKSCluster[] = [];
  for (const item of data) {
    if (!item || typeof item !== 'object') continue;
    const c = item as EKSCluster;
    if (!isSwitchableCluster(c)) continue;
    const key = `${c.name}|${c.region}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(c);
    if (out.length >= MAX_CLUSTERS) break;
  }
  return out;
}

/**
 * The error kind the hook reports, deriving 'incomplete' when a ready read returned nothing because
 * the fleet scan was partial (complete===false) rather than because there genuinely are no clusters.
 * `complete` is always null this task, so 'incomplete' only fires once a backend reports completeness.
 */
export function deriveErrorKind(
  status: K8sClustersStatus,
  clusterCount: number,
  complete: boolean | null,
  errorKind: ApiErrorKind | 'invalid' | null,
): ApiErrorKind | 'incomplete' | null {
  if (status === 'ready' && clusterCount === 0 && complete === false) return 'incomplete';
  return errorKind;
}

/** Accepts a bare array OR a `{clusters:[...]}` object; returns null when neither (an invalid body). */
function normalizeBody(data: unknown): ClusterListResult | null {
  if (Array.isArray(data)) {
    const clusters = sanitizeClusters(data);
    return clusters === null ? null : { clusters, complete: null, failedRegions: [] };
  }
  if (data && typeof data === 'object' && 'clusters' in (data as Record<string, unknown>)) {
    const clusters = sanitizeClusters((data as { clusters: unknown }).clusters);
    return clusters === null ? null : { clusters, complete: null, failedRegions: [] };
  }
  return null;
}

export function useK8sClusters(accountId: string | null): UseK8sClusters {
  const [status, setStatus] = useState<K8sClustersStatus>('loading');
  const [clusters, setClusters] = useState<EKSCluster[]>([]);
  const [complete, setComplete] = useState<boolean | null>(null);
  const [failedRegions, setFailedRegions] = useState<string[]>([]);
  const [errorKind, setErrorKind] = useState<ApiErrorKind | 'invalid' | null>(null);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [attempt, setAttempt] = useState(0);
  // Flips the next fetch to forceRefresh=true; a forced read bypasses the cache and rescans.
  const forceRef = useRef(false);
  const latest = useRef(accountId);
  latest.current = accountId;

  useEffect(() => {
    // Switching account clears everything before any cache read or fetch, so one account's clusters
    // are never visible under another.
    setClusters([]);
    setComplete(null);
    setFailedRegions([]);
    setErrorKind(null);
    setStatus('loading');

    if (!accountId) {
      setIsRefreshing(false);
      setStatus('error');
      return undefined;
    }

    const force = forceRef.current;
    forceRef.current = false;
    const scope = { accountId };

    if (!force) {
      const cached = clusterListCache.get(scope);
      if (cached) {
        setClusters(cached.clusters);
        setComplete(cached.complete);
        setFailedRegions(cached.failedRegions);
        setStatus('ready');
        setIsRefreshing(false);
        return undefined;
      }
    } else {
      setIsRefreshing(true);
    }

    let live = true;
    apiClient
      .get<unknown>(`/api/xamops/k8s/clusters?accountIds=${encodeURIComponent(accountId)}&forceRefresh=${force}`)
      .then((data) => {
        if (!live || latest.current !== accountId) return;
        const result = normalizeBody(data);
        if (result === null) {
          setErrorKind('invalid');
          setStatus('error');
          setClusters([]);
          setComplete(null);
          setFailedRegions([]);
          return;
        }
        // Never cache an empty list: a prior empty read must not masquerade as "no clusters".
        if (result.clusters.length > 0) clusterListCache.set(scope, result);
        setClusters(result.clusters);
        setComplete(result.complete);
        setFailedRegions(result.failedRegions);
        setErrorKind(null);
        setStatus('ready');
      })
      .catch((err) => {
        if (!live || latest.current !== accountId) return;
        setErrorKind(classifyApiError(err).kind);
        setStatus('error');
        setClusters([]);
        setComplete(null);
        setFailedRegions([]);
      })
      .finally(() => {
        if (!live || latest.current !== accountId) return;
        setIsRefreshing(false);
      });
    return () => { live = false; };
  }, [accountId, attempt]);

  const reload = useCallback(() => {
    forceRef.current = false;
    if (accountId) clusterListCache.delete({ accountId });
    setAttempt((n) => n + 1);
  }, [accountId]);

  const rescan = useCallback(() => {
    forceRef.current = true;
    if (accountId) clusterListCache.delete({ accountId });
    setAttempt((n) => n + 1);
  }, [accountId]);

  // A ready read that came back empty from a partial scan is "incomplete", not plain "no clusters".
  const derivedErrorKind = deriveErrorKind(status, clusters.length, complete, errorKind);

  return { status, clusters, complete, failedRegions, errorKind: derivedErrorKind, isRefreshing, reload, rescan };
}

export default useK8sClusters;
