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
}

export interface TeamUserForm {
  id: number | null;
  username: string;
  email: string;
  password: string;
  accountIds: number[];
  allowedTabs: string[];
}

export interface PasswordChangeForm {
  oldPassword: string;
  newPassword: string;
  confirmPassword: string;
}
