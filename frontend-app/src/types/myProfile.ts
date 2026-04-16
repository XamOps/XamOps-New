/**
 * TypeScript types for My Profile page
 */

export interface CloudAccount {
  id: number;
  accountName: string;
  provider: 'AWS' | 'GCP' | 'Azure';
}

export interface UserProfile {
  id: number | null;
  username: string;
  email: string;
  role: string;
  clientName: string;
  isPrimaryUser: boolean;
  cloudAccounts: CloudAccount[];
  allowedTabs?: string[];
}

export interface TeamUser {
  id: number;
  username: string;
  email: string;
  role: string;
  cloudAccounts: CloudAccount[];
  accountIds?: number[];
  allowedTabs?: string[];
  allAccountsAccess?: boolean;
}

export interface TeamUserForm {
  id: number | null;
  username: string;
  email: string;
  password: string;
  role: string; // 'ROLE_BILLOPS_ADMIN' (sub-user) or 'ROLE_BILLOPS_ADMIN_SUB' (sub-admin)
  accountIds: number[];
  allowedTabs: string[];
  allAccountsAccess: boolean;
}

export interface PasswordChangeForm {
  oldPassword: string;
  newPassword: string;
  confirmPassword: string;
}
