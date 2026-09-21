/**
 * Cloud permission health — why a page is missing data.
 *
 * Mirrors com.xammer.cloud.permissions on the backend. When a customer edits or
 * removes the IAM policy XamOps onboarded with, the affected pages simply go
 * empty; these types carry the explanation back to the UI so we can say which
 * action is missing instead of showing a silent blank.
 */

export type PermissionGapKind =
  /**
   * The identity is missing the action — an AWS policy edited or role replaced, a GCP role
   * removed from the service account, or an Azure role assignment dropped.
   */
  | 'PERMISSION'
  /**
   * The service itself is switched off for the account: not opted in on AWS, or the API
   * disabled on a GCP project. A different fix from a permission, hence a separate kind.
   */
  | 'SERVICE_NOT_ENABLED'
  /**
   * XamOps cannot authenticate at all, so nothing loads: an unassumable AWS role, a revoked
   * GCP service-account key, or an expired Azure client secret.
   */
  | 'ROLE_UNREACHABLE';

export type AccountHealth = 'HEALTHY' | 'DEGRADED' | 'UNREACHABLE';

export interface PermissionGap {
  accountId: string;
  provider: string;
  kind: PermissionGapKind;
  /** IAM service prefix, e.g. "ec2". */
  service: string;
  /** Human label, e.g. "EC2". */
  serviceLabel: string;
  operation: string | null;
  /** Full IAM action, e.g. "ec2:DescribeInstances". */
  action: string;
  regions: string[];
  errorCode: string | null;
  message: string | null;
  /** XamOpsFeature enum names. */
  features: string[];
  featureLabels: string[];
  firstSeen: string | null;
  lastSeen: string | null;
  occurrences: number;
}

export interface AccountPermissionStatus {
  accountId: string;
  accountName: string | null;
  provider: string;
  accessType: string | null;
  health: AccountHealth;
  /** Ready-to-show sentence built by the backend. Null when healthy. */
  summary: string | null;
  gaps: PermissionGap[];
  missingActions: string[];
  affectedFeatures: string[];
}

export interface PermissionStatusResponse {
  accounts: AccountPermissionStatus[];
  hasGaps: boolean;
  impactedAccountCount: number;
  feature: string | null;
}

/**
 * Feature key for the page currently on screen, so the banner reports only the
 * gaps that actually blank out what the user is looking at.
 *
 * Order matters: the longest matching prefix wins, so /media-ops/storage resolves
 * to MEDIAOPS rather than falling through.
 */
const ROUTE_FEATURES: Array<[string, string]> = [
  ['/cloudlist', 'CLOUD_LIST'],
  ['/unified-dashboard', 'DASHBOARD'],
  ['/dashboard', 'DASHBOARD'],
  ['/finops', 'FINOPS'],
  ['/cost', 'COST'],
  ['/waste', 'WASTE'],
  ['/ebs-rightsizing', 'EBS_RIGHTSIZING'],
  ['/rightsizing', 'RIGHTSIZING'],
  ['/reservation', 'RESERVATIONS'],
  ['/risp-coverage', 'RESERVATIONS'],
  ['/xamsecure', 'SECURITY'],
  ['/security', 'SECURITY'],
  ['/cloudk8s', 'KUBERNETES'],
  ['/eks-details', 'KUBERNETES'],
  ['/performance', 'PERFORMANCE'],
  ['/cloudmap', 'CLOUD_MAP'],
  ['/alerts', 'ALERTS'],
  ['/cloudsitter', 'CLOUDSITTER'],
  ['/spot-automation', 'SPOT_AUTOMATION'],
  ['/autospotting', 'SPOT_AUTOMATION'],
  ['/asg-detail', 'SPOT_AUTOMATION'],
  ['/flex-compute', 'SPOT_AUTOMATION'],
  ['/dbops', 'DBOPS'],
  ['/dataops', 'DATAOPS'],
  ['/observability', 'OBSERVABILITY'],
  ['/cicd-pipelines', 'CICD'],
  ['/devops-in-the-box', 'CICD'],
  ['/aiops', 'AIOPS'],
  ['/sre', 'SRE'],
  ['/complianceops', 'COMPLIANCE'],
  ['/soc2-program', 'COMPLIANCE'],
  ['/media-ops', 'MEDIAOPS'],
  ['/billops-', 'BILLOPS'],
];

/**
 * Resolves a route to its feature key. Provider-prefixed variants (/gcp-waste,
 * /azure-cost) map to the same feature as their AWS counterpart, because the page
 * identity is shared and gap data now exists for all three providers.
 */
export const featureForRoute = (pathname: string): string | null => {
  const normalized = pathname.replace(/^\/(gcp|azure)-/, '/');
  let best: string | null = null;
  let bestLength = 0;
  for (const [prefix, feature] of ROUTE_FEATURES) {
    if (normalized.startsWith(prefix) && prefix.length > bestLength) {
      best = feature;
      bestLength = prefix.length;
    }
  }
  return best;
};
