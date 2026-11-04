/**
 * The cluster page's link: `/eks-details?name=&accountIds=&region=[&version=]`
 * (docs/cloudk8s/ui-revamp-design.md §5.1, §13).
 *
 * One parser for everything that reads the link, so the page and the sidebar's cluster sections
 * (implementation plan, Addendum 1) can never disagree about which cluster is in context. It lives
 * outside pages/EksDetailsPage.tsx so the sidebar, which is in every page's bundle, doesn't pull in
 * the page's chunk.
 */
import { parseVersionParam } from './k8sVersion';

/** §13 rules for the link's parameters. */
const CLUSTER_NAME = /^[0-9A-Za-z][A-Za-z0-9_-]{0,99}$/;
const ACCOUNT_ID = /^[0-9]{1,12}$/;
const DEMO_ACCOUNT_ID = /^demo-aws-[0-9A-Za-z-]+$/;
const REGION = /^[a-z]{2}(-gov)?-[a-z]+-\d$/;

/** The existing default when a link has no region (§13). */
export const DEFAULT_REGION = 'us-east-1';

export interface ClusterLink {
  clusterName: string;
  accountId: string;
  region: string;
  /** describeCluster's version, which the fleet puts in the link; null when the link has none. */
  version: string | null;
}

/** The link's cluster, account and region (§13), or null when one is missing or malformed. */
export function parseClusterLink(params: URLSearchParams, demo: boolean): ClusterLink | null {
  const clusterName = params.get('name');
  const accountId = params.get('accountIds');
  const region = params.get('region') || DEFAULT_REGION;
  if (!clusterName || !CLUSTER_NAME.test(clusterName)) return null;
  if (!accountId || !(ACCOUNT_ID.test(accountId) || (demo && DEMO_ACCOUNT_ID.test(accountId)))) return null;
  if (!REGION.test(region)) return null;
  // A version isn't required: without one the page shows it as not reported rather than guess.
  return { clusterName, accountId, region, version: parseVersionParam(params.get('version')) };
}

/** The cluster page's URL for a link: `name, region, accountIds[, version]`, in that order. */
export function serializeClusterLink(link: { clusterName: string; region: string; accountId: string; version?: string | null }): string {
  const params = new URLSearchParams({ name: link.clusterName, region: link.region, accountIds: link.accountId });
  // The version EKS reports for this cluster (describeCluster), so the cluster page never guesses one.
  if (link.version) params.set('version', link.version);
  return `/eks-details?${params.toString()}`;
}

/** Whether a listed cluster passes the same link rules the page applies, so the switcher never offers a link the page rejects. */
export function isSwitchableCluster(c: { name?: unknown; region?: unknown }): boolean {
  return typeof c.name === 'string' && CLUSTER_NAME.test(c.name) && typeof c.region === 'string' && REGION.test(c.region);
}

/** Params that name something on the old cluster (or the old cluster itself) and never carry to another one. */
const DROPPED_ON_SWITCH = ['name', 'region', 'accountIds', 'version', 'workload', 'hpa', 'phase'];

/**
 * The cluster page link for switching to `target` in place: the target's identity first, then every
 * other current param (tab, sub, debug) in its original order. The old version can never leak onto a
 * target that has none.
 */
export function clusterSwitchHref(
  currentSearch: string,
  target: { name: string; region: string; version?: string | null },
  accountId: string,
): string {
  const rest = new URLSearchParams(currentSearch);
  for (const key of DROPPED_ON_SWITCH) rest.delete(key);
  const result = new URLSearchParams({ name: target.name, region: target.region, accountIds: accountId });
  if (typeof target.version === 'string' && target.version) result.set('version', target.version);
  rest.forEach((value, key) => result.append(key, value));
  return `/eks-details?${result.toString()}`;
}
