/**
 * The account's cluster list for the cluster switcher (Phase A design §3.3): the same read
 * `CloudK8sPage` uses, cached per-identity by account only (region and cluster are irrelevant to
 * "every cluster on this account"), and filtered to links the page itself would accept.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import apiClient from '../utils/apiClient';
import { createK8sCache } from '../utils/k8sCache';
import { isSwitchableCluster } from '../utils/k8sClusterLink';
import type { EKSCluster } from '../pages/CloudK8sPage';

export type K8sClustersStatus = 'loading' | 'ready' | 'error';

export interface UseK8sClusters {
  status: K8sClustersStatus;
  clusters: EKSCluster[];
  reload: () => void;
}

const MAX_CLUSTERS = 200;
const clusterListCache = createK8sCache<EKSCluster[]>('k8s-clusters');

function sanitize(data: unknown): EKSCluster[] | null {
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

export function useK8sClusters(accountId: string | null): UseK8sClusters {
  const [status, setStatus] = useState<K8sClustersStatus>('loading');
  const [clusters, setClusters] = useState<EKSCluster[]>([]);
  const [attempt, setAttempt] = useState(0);
  const latest = useRef(accountId);
  latest.current = accountId;

  useEffect(() => {
    if (!accountId) {
      setStatus('error');
      setClusters([]);
      return undefined;
    }
    const scope = { accountId };
    const cached = clusterListCache.get(scope);
    if (cached) {
      setClusters(cached);
      setStatus('ready');
      return undefined;
    }
    let live = true;
    setStatus('loading');
    apiClient
      .get<unknown>(`/api/xamops/k8s/clusters?accountIds=${encodeURIComponent(accountId)}&forceRefresh=false`)
      .then((data) => {
        if (!live || latest.current !== accountId) return;
        const list = sanitize(data);
        if (list === null) {
          setStatus('error');
          setClusters([]);
          return;
        }
        clusterListCache.set(scope, list);
        setClusters(list);
        setStatus('ready');
      })
      .catch(() => {
        if (!live || latest.current !== accountId) return;
        setStatus('error');
        setClusters([]);
      });
    return () => { live = false; };
  }, [accountId, attempt]);

  const reload = useCallback(() => {
    if (accountId) clusterListCache.delete({ accountId });
    setAttempt((n) => n + 1);
  }, [accountId]);

  return { status, clusters, reload };
}

export default useK8sClusters;
