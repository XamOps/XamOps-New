/**
 * The cluster's Kubernetes version, from a source that reported it, or null (never a guess).
 *
 * Two sources: the cluster link's `version`, which the fleet fills from describeCluster ("1.36"),
 * and the XamOps agent's API-server gitVersion ("v1.36.4-eks-a737599"). The dashboard carries the
 * agent's in `clusterOverview.version`, but when the agent reported none the backend fills that field
 * with the request's version or its own default, so only a gitVersion (which starts with "v") counts
 * as the agent's.
 */

/** "1.36", "v1.36.4", "v1.36.4-eks-a737599". */
const VERSION = /^v?\d+\.\d+(\.\d+)?([-+][0-9A-Za-z.-]+)?$/;
/** A Kubernetes gitVersion, which only the agent puts in the dashboard. */
const GIT_VERSION = /^v\d+\.\d+/;

/** The link's `version` when well formed, else null. */
export function parseVersionParam(raw: string | null | undefined): string | null {
  return raw && VERSION.test(raw) ? raw : null;
}

/** The agent's gitVersion when the dashboard has one, else the link's version, else null. */
export function resolveClusterVersion(linkVersion: string | null, dashboardVersion: string | null | undefined): string | null {
  if (dashboardVersion && GIT_VERSION.test(dashboardVersion) && VERSION.test(dashboardVersion)) return dashboardVersion;
  return linkVersion;
}

/** "v1.36.4" for display: a leading v, without the build suffix. */
export function displayVersion(version: string): string {
  return `v${version.replace(/^v/, '').split(/[-+]/)[0]}`;
}
