/**
 * Type definitions for User Management
 *
 * Mirrors the nested IAM DTOs in DashboardData.java. The risk fields are populated by
 * IamPrivilegeAnalyzer and are AWS-only today — GCP and Azure return the inventory shape
 * without them, so every risk field is optional and the UI must treat absent as "not
 * assessed" rather than as "clean".
 */

/** Matches SecuritySeverity.java, plus NONE for an identity with no findings. */
export type RiskLevel = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'NONE';

/** One reason an identity is over-privileged. Mirrors DashboardData.IamAccessRisk. */
export interface IamAccessRisk {
  /** Stable code, e.g. "IAM-EXC-001". */
  code: string;
  severity: RiskLevel;
  /** Short label for the chip on the card. */
  title: string;
  description: string;
  /** The policy the risk came from, when attributable to one. */
  sourcePolicy?: string | null;
  /** Offending actions, already capped server-side. */
  evidence?: string[] | null;
}

export interface Policy {
  policyName: string;
  policyArn?: string;
  type?: 'Managed' | 'Inline';

  /** False when the document was never fetched or the read was denied. */
  documentAnalyzed?: boolean;
  /** Grants Action:* on Resource:*. */
  adminAccess?: boolean;
  /** Service-wide wildcards, e.g. "s3:*". */
  wildcardActions?: string[] | null;
  /** Actions permitting self-escalation, e.g. "iam:PutUserPolicy". */
  escalationActions?: string[] | null;
  /**
   * Group the policy is inherited from, or absent when attached directly.
   *
   * <p>Shown because it changes the remediation: an inherited grant cannot be removed from
   * the user, only from the group, which affects every other member too.
   */
  inheritedFrom?: string | null;
}

/** Fields shared by users and roles, so the card canvas can render either. */
export interface IamRiskFields {
  riskLevel?: RiskLevel;
  riskScore?: number;
  risks?: IamAccessRisk[] | null;
  /** True only at HIGH or CRITICAL. This is what paints the card red. */
  excessPermissions?: boolean;
}

export interface IamUser extends IamRiskFields {
  userId: string;
  userName: string;
  arn: string;
  createDate: string;
  passwordLastUsed: string | null;
  groups: string[];
  attachedPolicies: Policy[];
  mfaEnabled: boolean;
}

export interface IamRole extends IamRiskFields {
  roleId: string;
  roleName: string;
  arn: string;
  createDate: string;
  description?: string;
  attachedPolicies: Policy[];
}

export interface IamDetails {
  users: IamUser[];
  roles: IamRole[];

  /**
   * True when some policy documents could not be read.
   *
   * <p>Without this the UI cannot distinguish an audited-clean account from an
   * unaudited one — both render as cards with no red flags.
   */
  permissionAnalysisDegraded?: boolean;
  permissionAnalysisNote?: string | null;

  /**
   * True when the inventory is complete but the privilege audit is still running.
   *
   * <p>Separate from `permissionAnalysisDegraded`, which means the audit ran and hit something
   * it could not read. The audit is two IAM calls per distinct policy run serially, so on a
   * large account it does not fit inside the request; the backend returns the inventory and
   * finishes scoring in the background. Without this the UI would present unscored identities
   * the same way it presents an account with no findings.
   */
  permissionAnalysisPending?: boolean;

  /**
   * When this scan was produced, as an ISO-8601 instant.
   *
   * <p>Present because the backend serves this from a durable snapshot that can outlive a
   * working day, not only from a live scan. Absent on GCP and Azure, which assemble the
   * inventory inside their own dashboard aggregates and never pass through that cache.
   */
  lastUpdated?: string | null;

  /**
   * True when this came from cache and the server is already rebuilding it.
   *
   * <p>Separate from `permissionAnalysisPending`, which is about the risk-scoring half of one
   * scan. This says the whole snapshot is being replaced — the normal path after it ages out or
   * after an IAM change event — so a newer answer is coming without the user asking for it.
   */
  refreshing?: boolean;
}

/**
 * Response of `GET /api/xamops/security/identities`.
 *
 * <p>Mirrors IdentityInventoryResponse.java. This replaced reading identities out of the full
 * dashboard aggregate: that endpoint joins ~18 AWS collectors, so the Identity tab waited on
 * Cost Explorer and waste detection before it could draw an IAM list, and timed out at the
 * gateway on cold caches.
 */
export interface IdentityInventoryResponse {
  provider?: 'AWS' | 'GCP' | 'Azure';
  accountId?: string;
  accountName?: string;
  iamDetails?: IamDetails | null;
}

/** A user or a role, for components that render both. */
export type IamIdentity = IamUser | IamRole;

export function isIamUser(identity: IamIdentity): identity is IamUser {
  return 'userName' in identity;
}

/** Display name, regardless of identity kind. */
export function identityName(identity: IamIdentity): string {
  return isIamUser(identity) ? identity.userName : identity.roleName;
}

/** Stable React key, regardless of identity kind. */
export function identityKey(identity: IamIdentity): string {
  return isIamUser(identity) ? identity.userId : identity.roleId;
}
