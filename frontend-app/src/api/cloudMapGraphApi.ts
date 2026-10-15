import { apiFetch } from '../utils/apiClient';
import type { GraphElement } from '../types/cloudmap';

/**
 * The account topology, for the cost drawer's Connectivity tab.
 *
 * <p>The fetcher only — no query key is exported, deliberately. The Cloud Map page stores its
 * all-VPCs topology under `['cloudmap','graph',accountId,'__ALL__']`, merged from one request per
 * VPC with every node id rewritten to `vpcId::nodeId`. This fetcher's single `vpcId=ALL` request
 * returns unscoped ids, so the two payloads are not interchangeable in either direction: reading
 * that entry would hand the tab scoped ids, and writing this payload into it would leave the Cloud
 * Map canvas rendering parent references it no longer satisfies.
 *
 * <p>`hooks/useCloudMap` is not re-pointed at this. Its two inline fetches have different and
 * load-bearing error semantics — one keeps a per-VPC failure tally, the other renders its thrown
 * message on the page — and the tab needs neither.
 */
export async function fetchCloudMapGraph(args: {
  accountId: string;
  /**
   * `'ALL'` for the whole estate in one request, which is what the connectivity tab passes.
   *
   * <p>Omitting it is the backend's "global view" branch and filters to resources with *no* VPC,
   * so an EC2 instance would not be in the payload at all.
   */
  vpcId?: string;
  /** Region code. The backend's VPC and subnet scaffolding is region-scoped. */
  region?: string;
  /** Never set by the cost drawer: it would trigger a ~1,000-call account rescan. */
  forceRefresh?: boolean;
  /** Sized by the caller. `apiFetch`'s 45 s default aborts most cold account scans. */
  timeoutMs?: number;
}): Promise<GraphElement[]> {
  const params = new URLSearchParams({ accountId: args.accountId });
  params.set('vpcId', args.vpcId ?? '');
  params.set('region', args.region ?? '');
  if (args.forceRefresh) params.set('forceRefresh', 'true');
  const url = `/api/xamops/cloudmap/graph?${params}`;

  const response = await apiFetch(url, { timeoutMs: args.timeoutMs });
  if (!response.ok) {
    /*
     * Message pinned verbatim to useCloudMap.loadGraph's, which renders error.message into
     * graphError on the Cloud Map page. Nothing depends on it here — the tab renders its own
     * sentence — but pinning it means unifying the two fetchers later is provably
     * behaviour-preserving rather than a rewrite of user-visible text.
     */
    throw new Error(`HTTP error! status: ${response.status}`);
  }

  const body = await response.json().catch(() => null);
  if (!Array.isArray(body)) {
    /*
     * A malformed 200 is a failed read, not an empty account, and the two render differently: an
     * empty array is the backend's own collapsed-failure path — CloudMapService turns nearly every
     * internal failure into a successful empty list — so the tab has to reason about `[]` as a
     * value, while a non-array body is something answering in the service's place.
     */
    console.warn('[cloudmap] Ignoring unexpected graph response shape from', url);
    throw new Error('cloudmap/graph: unexpected shape');
  }
  return body as GraphElement[];
}
