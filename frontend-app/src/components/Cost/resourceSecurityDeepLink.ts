/**
 * The resource-focus predicates shared by the three product pages that honour the Cost drawer's
 * `?resource=` deep link.
 *
 * <p>One module so the match a page applies is the match the tests assert, byte-for-byte. Each is
 * additive and read-only: when `resource` is null the page is unchanged.
 */

/**
 * Security Center — ARN-tolerant.
 *
 * <p>{@code /security/findings} merges three sources. Only the live projection sets the finding's
 * {@code resourceId} to the ARN; Prowler and describe-based rows carry a resource NAME. The
 * aggregator always deep-links on the ARN (the one id every ARN-keyed row holds), so a finding
 * matches when its id equals the param, or the param (an ARN) ends with the finding's shorter id as
 * its {@code :}- or {@code /}-delimited tail.
 */
export const securityFindingMatchesResource = (
  findingResourceId: string | null | undefined,
  resource: string | null,
): boolean => {
  if (!resource) return true;
  if (!findingResourceId) return false;
  return (
    findingResourceId === resource
    || resource.endsWith(`:${findingResourceId}`)
    || resource.endsWith(`/${findingResourceId}`)
  );
};

/**
 * Performance Insights — exact match on the inventory short id, which is the id the aggregator
 * deep-links on.
 */
export const performanceInsightMatchesResource = (
  insightResourceId: string | null | undefined,
  resource: string | null,
): boolean => {
  if (!resource) return true;
  return insightResourceId === resource;
};

/**
 * ComplianceOps — a control focuses when one of its listed resources carries the exact matched
 * {@code uid} the aggregator emitted.
 */
export const complianceControlMatchesResource = (
  resourceUids: (string | null)[],
  resource: string | null,
): boolean => {
  if (!resource) return true;
  return resourceUids.some((uid) => uid === resource);
};
