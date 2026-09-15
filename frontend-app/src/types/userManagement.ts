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
}

export interface UserManagementData {
  selectedAccount?: {
    id: string;
    name: string;
    iamDetails?: IamDetails;
  };
  selectedProvider?: 'AWS' | 'GCP' | 'Azure';
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
