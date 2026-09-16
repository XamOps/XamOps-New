/**
 * Demo data for the Security Center's Identity & Access tab.
 *
 * <p>Shaped as `IdentityInventoryResponse` (see types/userManagement.ts and the Java DTO of the
 * same name), because that tab now calls `GET /api/xamops/security/identities` rather than reading
 * one field out of the dashboard aggregate.
 *
 * <p>Its own module rather than more lines in xamopsMockData.ts: this is the only mock that has to
 * satisfy a real analyzer's output contract. The risk codes below are the actual constants from
 * IamPrivilegeAnalyzer (IAM-EXC-001..007), and `accessLevelOf` in accessGraphModel derives each
 * principal's privilege lane from `adminAccess` / `escalationActions` / `wildcardActions` /
 * `documentAnalyzed` — so those flags are not decoration, they decide which lane a node lands in.
 * Setting them carelessly produces a demo where every identity sits in one lane, which is exactly
 * the flat picture the access graph exists to replace.
 *
 * <p>The estate is deliberately uneven: an unprotected administrator, a CI principal that can
 * escalate itself, a stale admin credential, wildcard holders, several genuinely scoped
 * identities, and one that could not be read at all. That spread is what makes the graph's lanes,
 * the risk KPIs, the MFA count and the "not assessed" state all visible without hunting.
 */

import type { IamRole, IamUser, Policy } from '../types/userManagement';

// ─── Policy builders ─────────────────────────────────────────────────────────

/** AWS-managed policy, document read, nothing found. */
const scoped = (policyName: string, inheritedFrom?: string): Policy => ({
  policyName,
  policyArn: `arn:aws:iam::aws:policy/${policyName}`,
  type: 'Managed',
  documentAnalyzed: true,
  adminAccess: false,
  wildcardActions: [],
  escalationActions: [],
  ...(inheritedFrom ? { inheritedFrom } : {}),
});

/** Customer-managed policy, document read, nothing found. */
const scopedCustom = (policyName: string, inheritedFrom?: string): Policy => ({
  policyName,
  policyArn: `arn:aws:iam::482036498712:policy/${policyName}`,
  type: 'Managed',
  documentAnalyzed: true,
  adminAccess: false,
  wildcardActions: [],
  escalationActions: [],
  ...(inheritedFrom ? { inheritedFrom } : {}),
});

const adminPolicy = (inheritedFrom?: string): Policy => ({
  policyName: 'AdministratorAccess',
  policyArn: 'arn:aws:iam::aws:policy/AdministratorAccess',
  type: 'Managed',
  documentAnalyzed: true,
  adminAccess: true,
  wildcardActions: [],
  escalationActions: [],
  ...(inheritedFrom ? { inheritedFrom } : {}),
});

const wildcardPolicy = (policyName: string, actions: string[], inheritedFrom?: string): Policy => ({
  policyName,
  policyArn: `arn:aws:iam::482036498712:policy/${policyName}`,
  type: 'Managed',
  documentAnalyzed: true,
  adminAccess: false,
  wildcardActions: actions,
  escalationActions: [],
  ...(inheritedFrom ? { inheritedFrom } : {}),
});

const escalationPolicy = (policyName: string, actions: string[]): Policy => ({
  policyName,
  policyArn: `arn:aws:iam::482036498712:policy/${policyName}`,
  type: 'Managed',
  documentAnalyzed: true,
  adminAccess: false,
  wildcardActions: [],
  escalationActions: actions,
});

/** Inline policy whose document could not be read — drives the "not assessed" lane. */
const unreadInline = (policyName: string): Policy => ({
  policyName,
  type: 'Inline',
  documentAnalyzed: false,
});

// ─── Production estate (demo-aws-001) ────────────────────────────────────────

const prodUsers: IamUser[] = [
  {
    userId: 'AIDADEMO0000000000001',
    userName: 'aditya.mehta',
    arn: 'arn:aws:iam::482036498712:user/aditya.mehta',
    createDate: '2023-02-14T09:12:00Z',
    passwordLastUsed: '2026-09-15T06:40:00Z',
    groups: ['Administrators'],
    attachedPolicies: [adminPolicy('Administrators')],
    mfaEnabled: false,
    riskLevel: 'CRITICAL',
    riskScore: 95,
    excessPermissions: true,
    risks: [
      {
        code: 'IAM-EXC-005',
        severity: 'CRITICAL',
        title: 'Over-broad AWS policy: AdministratorAccess',
        description:
          'The AdministratorAccess policy (inherited from group Administrators) grants unrestricted '
          + 'access to every service and resource. Replace it with a scoped policy covering only the '
          + 'actions in use.',
        sourcePolicy: 'AdministratorAccess (via Administrators)',
        evidence: [],
      },
      {
        code: 'IAM-EXC-006',
        severity: 'HIGH',
        title: 'Administrator without MFA',
        description:
          'This user can perform any action in the account and is protected by a password alone. '
          + 'A single credential leak is a full account compromise.',
        sourcePolicy: null,
        evidence: [],
      },
    ],
  },
  {
    userId: 'AIDADEMO0000000000002',
    userName: 'ci-deploy',
    arn: 'arn:aws:iam::482036498712:user/ci-deploy',
    createDate: '2024-06-03T11:45:00Z',
    passwordLastUsed: 'Never',
    groups: ['Engineering'],
    attachedPolicies: [
      escalationPolicy('XamOpsDeployPipeline', ['iam:passrole', 'iam:attachrolepolicy', 'iam:createrole']),
      scoped('AmazonEC2ContainerRegistryPowerUser', 'Engineering'),
    ],
    mfaEnabled: false,
    riskLevel: 'CRITICAL',
    riskScore: 88,
    excessPermissions: true,
    risks: [
      {
        code: 'IAM-EXC-003',
        severity: 'CRITICAL',
        title: 'Can escalate its own privileges',
        description:
          'Holds IAM actions that allow granting itself additional permissions, so its effective '
          + 'access is unbounded regardless of what else is attached.',
        sourcePolicy: 'XamOpsDeployPipeline',
        evidence: ['iam:passrole', 'iam:attachrolepolicy', 'iam:createrole'],
      },
    ],
  },
  {
    userId: 'AIDADEMO0000000000003',
    userName: 'data-analyst',
    arn: 'arn:aws:iam::482036498712:user/data-analyst',
    createDate: '2024-11-20T08:05:00Z',
    passwordLastUsed: '2026-09-12T14:22:00Z',
    groups: ['Analytics'],
    attachedPolicies: [
      wildcardPolicy('AnalyticsWorkbench', ['s3:*', 'athena:*', 'glue:*'], 'Analytics'),
      scoped('AmazonAthenaFullAccess', 'Analytics'),
    ],
    mfaEnabled: true,
    riskLevel: 'HIGH',
    riskScore: 62,
    excessPermissions: true,
    risks: [
      {
        code: 'IAM-EXC-002',
        severity: 'HIGH',
        title: 'Service-wide wildcard permissions',
        description: 'Grants every action in 3 services rather than the specific actions needed.',
        sourcePolicy: 'AnalyticsWorkbench (via Analytics)',
        evidence: ['s3:*', 'athena:*', 'glue:*'],
      },
    ],
  },
  {
    userId: 'AIDADEMO0000000000004',
    userName: 'finance-readonly',
    arn: 'arn:aws:iam::482036498712:user/finance-readonly',
    createDate: '2025-01-08T10:30:00Z',
    passwordLastUsed: '2026-09-14T09:15:00Z',
    groups: ['Finance'],
    attachedPolicies: [scoped('AWSBillingReadOnlyAccess', 'Finance'), scopedCustom('CostExplorerReadOnly')],
    mfaEnabled: true,
    riskLevel: 'NONE',
    riskScore: 0,
    excessPermissions: false,
    risks: [],
  },
  {
    userId: 'AIDADEMO0000000000005',
    userName: 'contractor-temp',
    arn: 'arn:aws:iam::482036498712:user/contractor-temp',
    createDate: '2026-08-01T13:00:00Z',
    passwordLastUsed: '2026-09-02T16:48:00Z',
    groups: [],
    // Nothing readable, so the analyzer leaves the level absent rather than reporting "clean".
    attachedPolicies: [unreadInline('contractor-inline-access')],
    mfaEnabled: false,
    riskScore: 0,
    excessPermissions: false,
    risks: [],
  },
  {
    userId: 'AIDADEMO0000000000006',
    userName: 'backup-operator',
    arn: 'arn:aws:iam::482036498712:user/backup-operator',
    createDate: '2024-03-19T07:20:00Z',
    passwordLastUsed: '2026-09-08T11:02:00Z',
    groups: ['Engineering'],
    attachedPolicies: [scoped('AWSBackupOperatorAccess', 'Engineering')],
    mfaEnabled: true,
    riskLevel: 'NONE',
    riskScore: 0,
    excessPermissions: false,
    risks: [],
  },
  {
    userId: 'AIDADEMO0000000000007',
    userName: 'legacy-svc-account',
    arn: 'arn:aws:iam::482036498712:user/legacy-svc-account',
    createDate: '2021-07-02T12:00:00Z',
    passwordLastUsed: '2022-11-30T08:00:00Z',
    groups: ['Administrators'],
    attachedPolicies: [adminPolicy('Administrators')],
    mfaEnabled: false,
    riskLevel: 'CRITICAL',
    riskScore: 92,
    excessPermissions: true,
    risks: [
      {
        code: 'IAM-EXC-005',
        severity: 'CRITICAL',
        title: 'Over-broad AWS policy: AdministratorAccess',
        description:
          'The AdministratorAccess policy (inherited from group Administrators) grants unrestricted '
          + 'access to every service and resource.',
        sourcePolicy: 'AdministratorAccess (via Administrators)',
        evidence: [],
      },
      {
        code: 'IAM-EXC-007',
        severity: 'MEDIUM',
        title: 'Unused administrator credential',
        description:
          'This user holds broad permissions but has not signed in for over 90 days, so the access '
          + 'is a standing risk with no demonstrated need.',
        sourcePolicy: null,
        evidence: [],
      },
    ],
  },
];

const prodRoles: IamRole[] = [
  {
    roleId: 'ARODEMO0000000000001',
    roleName: 'terraform-executor',
    arn: 'arn:aws:iam::482036498712:role/terraform-executor',
    createDate: '2023-09-01T09:00:00Z',
    description: 'Applies infrastructure changes from the platform pipeline.',
    attachedPolicies: [adminPolicy()],
    riskLevel: 'CRITICAL',
    riskScore: 90,
    excessPermissions: true,
    risks: [
      {
        code: 'IAM-EXC-001',
        severity: 'CRITICAL',
        title: 'Full administrator access',
        description: 'Grants every action on every resource (Action "*" on Resource "*").',
        sourcePolicy: 'AdministratorAccess',
        evidence: ['Action: * / Resource: *'],
      },
    ],
  },
  {
    roleId: 'ARODEMO0000000000002',
    roleName: 'github-actions-oidc',
    arn: 'arn:aws:iam::482036498712:role/github-actions-oidc',
    createDate: '2025-02-17T15:30:00Z',
    description: 'Federated role assumed by GitHub Actions via OIDC.',
    attachedPolicies: [
      escalationPolicy('PipelineRoleManagement', ['iam:putrolepolicy', 'iam:updateassumerolepolicy']),
      scoped('AmazonS3FullAccess'),
    ],
    riskLevel: 'CRITICAL',
    riskScore: 84,
    excessPermissions: true,
    risks: [
      {
        code: 'IAM-EXC-003',
        severity: 'CRITICAL',
        title: 'Can escalate its own privileges',
        description:
          'Holds IAM actions that allow granting itself additional permissions, so its effective '
          + 'access is unbounded regardless of what else is attached.',
        sourcePolicy: 'PipelineRoleManagement',
        evidence: ['iam:putrolepolicy', 'iam:updateassumerolepolicy'],
      },
      {
        code: 'IAM-EXC-005',
        severity: 'HIGH',
        title: 'Over-broad AWS policy: AmazonS3FullAccess',
        description: 'The AmazonS3FullAccess policy grants full access to every S3 bucket in the account.',
        sourcePolicy: 'AmazonS3FullAccess',
        evidence: [],
      },
    ],
  },
  {
    roleId: 'ARODEMO0000000000003',
    roleName: 'prod-eks-node-role',
    arn: 'arn:aws:iam::482036498712:role/prod-eks-node-role',
    createDate: '2024-04-22T10:10:00Z',
    description: 'Instance role for the production EKS node group.',
    attachedPolicies: [
      wildcardPolicy('NodeGroupTelemetry', ['logs:*', 'cloudwatch:*']),
      scoped('AmazonEKSWorkerNodePolicy'),
      scoped('AmazonEKS_CNI_Policy'),
    ],
    riskLevel: 'HIGH',
    riskScore: 55,
    excessPermissions: true,
    risks: [
      {
        code: 'IAM-EXC-002',
        severity: 'HIGH',
        title: 'Service-wide wildcard permissions',
        description: 'Grants every action in 2 services rather than the specific actions needed.',
        sourcePolicy: 'NodeGroupTelemetry',
        evidence: ['logs:*', 'cloudwatch:*'],
      },
    ],
  },
  {
    roleId: 'ARODEMO0000000000004',
    roleName: 'XamOpsReadOnlyRole',
    arn: 'arn:aws:iam::482036498712:role/XamOpsReadOnlyRole',
    createDate: '2024-01-30T08:45:00Z',
    description: 'Cross-account read-only role used by XamOps to collect inventory.',
    attachedPolicies: [scoped('ReadOnlyAccess'), scopedCustom('XamOpsInventoryRead')],
    riskLevel: 'NONE',
    riskScore: 0,
    excessPermissions: false,
    risks: [],
  },
  {
    roleId: 'ARODEMO0000000000005',
    roleName: 'lambda-image-resize',
    arn: 'arn:aws:iam::482036498712:role/lambda-image-resize',
    createDate: '2025-06-11T14:05:00Z',
    description: 'Execution role for the image-resize function.',
    attachedPolicies: [scoped('AWSLambdaBasicExecutionRole'), scopedCustom('ResizeBucketReadWrite')],
    riskLevel: 'NONE',
    riskScore: 0,
    excessPermissions: false,
    risks: [],
  },
  {
    roleId: 'ARODEMO0000000000006',
    roleName: 'rds-monitoring-role',
    arn: 'arn:aws:iam::482036498712:role/rds-monitoring-role',
    createDate: '2024-08-05T09:55:00Z',
    description: 'Enhanced monitoring role for RDS instances.',
    attachedPolicies: [scoped('AmazonRDSEnhancedMonitoringRole')],
    riskLevel: 'NONE',
    riskScore: 0,
    excessPermissions: false,
    risks: [],
  },
  {
    roleId: 'ARODEMO0000000000007',
    roleName: 'incident-responder',
    arn: 'arn:aws:iam::482036498712:role/incident-responder',
    createDate: '2025-10-14T16:20:00Z',
    description: 'Break-glass role assumed during incidents.',
    attachedPolicies: [unreadInline('incident-inline-grants')],
    riskScore: 0,
    excessPermissions: false,
    risks: [],
  },
];

// ─── Staging estate (demo-aws-002) ───────────────────────────────────────────
// Smaller and cleaner, so switching accounts visibly changes the graph rather than
// showing the same tree under a different name.

const stagingUsers: IamUser[] = [
  {
    userId: 'AIDADEMOSTG000000001',
    userName: 'staging-deployer',
    arn: 'arn:aws:iam::905612340987:user/staging-deployer',
    createDate: '2025-03-12T09:00:00Z',
    passwordLastUsed: '2026-09-13T10:00:00Z',
    groups: ['Engineering'],
    attachedPolicies: [
      wildcardPolicy('StagingDeploy', ['ecs:*'], 'Engineering'),
      scoped('AmazonEC2ContainerRegistryPowerUser', 'Engineering'),
    ],
    mfaEnabled: true,
    riskLevel: 'HIGH',
    riskScore: 48,
    excessPermissions: true,
    risks: [
      {
        code: 'IAM-EXC-002',
        severity: 'HIGH',
        title: 'Service-wide wildcard permissions',
        description: 'Grants every action in 1 service rather than the specific actions needed.',
        sourcePolicy: 'StagingDeploy (via Engineering)',
        evidence: ['ecs:*'],
      },
    ],
  },
  {
    userId: 'AIDADEMOSTG000000002',
    userName: 'qa-automation',
    arn: 'arn:aws:iam::905612340987:user/qa-automation',
    createDate: '2025-07-30T11:25:00Z',
    passwordLastUsed: '2026-09-10T08:30:00Z',
    groups: ['Engineering'],
    attachedPolicies: [scoped('AmazonS3ReadOnlyAccess', 'Engineering')],
    mfaEnabled: true,
    riskLevel: 'NONE',
    riskScore: 0,
    excessPermissions: false,
    risks: [],
  },
];

const stagingRoles: IamRole[] = [
  {
    roleId: 'ARODEMOSTG000000001',
    roleName: 'staging-eks-node-role',
    arn: 'arn:aws:iam::905612340987:role/staging-eks-node-role',
    createDate: '2025-04-02T10:00:00Z',
    description: 'Instance role for the staging EKS node group.',
    attachedPolicies: [scoped('AmazonEKSWorkerNodePolicy'), scoped('AmazonEKS_CNI_Policy')],
    riskLevel: 'NONE',
    riskScore: 0,
    excessPermissions: false,
    risks: [],
  },
  {
    roleId: 'ARODEMOSTG000000002',
    roleName: 'staging-lambda-exec',
    arn: 'arn:aws:iam::905612340987:role/staging-lambda-exec',
    createDate: '2025-09-18T13:40:00Z',
    description: 'Execution role for staging functions.',
    attachedPolicies: [scoped('AWSLambdaBasicExecutionRole')],
    riskLevel: 'NONE',
    riskScore: 0,
    excessPermissions: false,
    risks: [],
  },
];

// ─── GCP (demo-gcp-001) ──────────────────────────────────────────────────────
// The tab relabels users as "Principals" and roles as "Custom Roles" off the provider field, and
// hides the MFA KPI, so the provider has to be reported rather than defaulted to AWS.

const gcpPrincipals: IamUser[] = [
  {
    userId: 'gcp-sa-etl',
    userName: 'analytics-etl@xammer-analytics.iam.gserviceaccount.com',
    arn: 'projects/xammer-analytics/serviceAccounts/analytics-etl',
    createDate: '2024-10-02T09:00:00Z',
    passwordLastUsed: null,
    groups: [],
    attachedPolicies: [
      {
        policyName: 'roles/editor',
        policyArn: 'roles/editor',
        type: 'Managed',
        documentAnalyzed: true,
        adminAccess: true,
        wildcardActions: [],
        escalationActions: [],
      },
    ],
    mfaEnabled: false,
    riskLevel: 'CRITICAL',
    riskScore: 86,
    excessPermissions: true,
    risks: [
      {
        code: 'IAM-EXC-005',
        severity: 'CRITICAL',
        title: 'Primitive role: roles/editor',
        description:
          'Primitive roles grant broad write access across every service in the project. Replace it '
          + 'with predefined roles scoped to the resources this principal actually uses.',
        sourcePolicy: 'roles/editor',
        evidence: [],
      },
    ],
  },
  {
    userId: 'gcp-sa-reporting',
    userName: 'reporting-reader@xammer-analytics.iam.gserviceaccount.com',
    arn: 'projects/xammer-analytics/serviceAccounts/reporting-reader',
    createDate: '2025-05-14T10:20:00Z',
    passwordLastUsed: null,
    groups: [],
    attachedPolicies: [
      {
        policyName: 'roles/bigquery.dataViewer',
        policyArn: 'roles/bigquery.dataViewer',
        type: 'Managed',
        documentAnalyzed: true,
        adminAccess: false,
        wildcardActions: [],
        escalationActions: [],
      },
    ],
    mfaEnabled: false,
    riskLevel: 'NONE',
    riskScore: 0,
    excessPermissions: false,
    risks: [],
  },
];

const gcpCustomRoles: IamRole[] = [
  {
    roleId: 'gcp-role-deployer',
    roleName: 'CustomDeployer',
    arn: 'projects/xammer-analytics/roles/CustomDeployer',
    createDate: '2025-01-09T08:00:00Z',
    description: 'Custom role used by the deployment pipeline.',
    attachedPolicies: [
      {
        policyName: 'CustomDeployer',
        policyArn: 'projects/xammer-analytics/roles/CustomDeployer',
        type: 'Managed',
        documentAnalyzed: true,
        adminAccess: false,
        wildcardActions: ['run.*'],
        escalationActions: [],
      },
    ],
    riskLevel: 'HIGH',
    riskScore: 44,
    excessPermissions: true,
    risks: [
      {
        code: 'IAM-EXC-002',
        severity: 'HIGH',
        title: 'Service-wide wildcard permissions',
        description: 'Grants every permission in 1 service rather than the specific ones needed.',
        sourcePolicy: 'CustomDeployer',
        evidence: ['run.*'],
      },
    ],
  },
];

// ─── Azure (demo-azure-001) ──────────────────────────────────────────────────

const azurePrincipals: IamUser[] = [
  {
    userId: 'azure-sp-platform',
    userName: 'sp-platform-automation',
    arn: '/subscriptions/8f2c/servicePrincipals/sp-platform-automation',
    createDate: '2024-12-01T09:00:00Z',
    passwordLastUsed: null,
    groups: [],
    attachedPolicies: [
      {
        policyName: 'Owner',
        policyArn: '/subscriptions/8f2c/providers/Microsoft.Authorization/roleDefinitions/Owner',
        type: 'Managed',
        documentAnalyzed: true,
        adminAccess: true,
        wildcardActions: [],
        escalationActions: [],
      },
    ],
    mfaEnabled: false,
    riskLevel: 'CRITICAL',
    riskScore: 89,
    excessPermissions: true,
    risks: [
      {
        code: 'IAM-EXC-001',
        severity: 'CRITICAL',
        title: 'Subscription Owner',
        description:
          'Owner grants full control of the subscription, including the ability to grant access to '
          + 'others. Assign a narrower built-in role scoped to the resource group in use.',
        sourcePolicy: 'Owner',
        evidence: ['Actions: * / NotActions: none'],
      },
    ],
  },
];

const azureRoleDefinitions: IamRole[] = [
  {
    roleId: 'azure-role-reader',
    roleName: 'Reader',
    arn: '/subscriptions/8f2c/providers/Microsoft.Authorization/roleDefinitions/Reader',
    createDate: '2024-12-01T09:00:00Z',
    description: 'View all resources without making changes.',
    attachedPolicies: [
      {
        policyName: 'Reader',
        policyArn: '/subscriptions/8f2c/providers/Microsoft.Authorization/roleDefinitions/Reader',
        type: 'Managed',
        documentAnalyzed: true,
        adminAccess: false,
        wildcardActions: [],
        escalationActions: [],
      },
    ],
    riskLevel: 'NONE',
    riskScore: 0,
    excessPermissions: false,
    risks: [],
  },
];

// ─── Route payload ───────────────────────────────────────────────────────────

/**
 * Identity inventory for a demo account.
 *
 * <p>Keyed on the ids in `demoAccounts`, so switching accounts in the sidebar changes the graph —
 * including across providers, where the tab's wording and KPI set change with it. An unknown or
 * missing id falls back to the production estate rather than returning nothing, because an empty
 * Identity tab in a demo reads as a broken feature rather than as an empty account.
 *
 * <p>Every account reports a complete audit. The degraded and still-scoring banners are real
 * states worth showing, but not by default: a demo that opens on a warning reads as a product
 * that does not work.
 */
export function demoIdentityInventory(accountId: string) {
  switch (accountId) {
    case 'demo-aws-002':
      return {
        provider: 'AWS' as const,
        accountId,
        accountName: 'Xammer Staging',
        iamDetails: {
          users: stagingUsers,
          roles: stagingRoles,
          permissionAnalysisDegraded: false,
          permissionAnalysisNote: null,
          permissionAnalysisPending: false,
        },
      };
    case 'demo-gcp-001':
      return {
        provider: 'GCP' as const,
        accountId,
        accountName: 'GCP Analytics',
        iamDetails: {
          users: gcpPrincipals,
          roles: gcpCustomRoles,
          permissionAnalysisDegraded: false,
          permissionAnalysisNote: null,
          permissionAnalysisPending: false,
        },
      };
    case 'demo-azure-001':
      return {
        provider: 'Azure' as const,
        accountId,
        accountName: 'Azure Enterprise',
        iamDetails: {
          users: azurePrincipals,
          roles: azureRoleDefinitions,
          permissionAnalysisDegraded: false,
          permissionAnalysisNote: null,
          permissionAnalysisPending: false,
        },
      };
    default:
      return {
        provider: 'AWS' as const,
        accountId: accountId || 'demo-aws-001',
        accountName: 'Xammer Production',
        iamDetails: {
          users: prodUsers,
          roles: prodRoles,
          permissionAnalysisDegraded: false,
          permissionAnalysisNote: null,
          permissionAnalysisPending: false,
        },
      };
  }
}
