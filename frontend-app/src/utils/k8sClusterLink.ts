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
