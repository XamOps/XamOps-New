/**
 * Type definitions for User Management
 */

export interface Policy {
  policyName: string;
  policyArn?: string;
  type?: 'Managed' | 'Inline';
}

export interface IamUser {
  userId: string;
  userName: string;
  arn: string;
  createDate: string;
  passwordLastUsed: string | null;
  groups: string[];
  attachedPolicies: Policy[];
  mfaEnabled: boolean;
}

export interface IamRole {
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
}

export interface UserManagementData {
  selectedAccount?: {
    id: string;
    name: string;
    iamDetails?: IamDetails;
  };
  selectedProvider?: 'AWS' | 'GCP' | 'Azure';
}
