/**
 * TypeScript types for My Profile page
 */

export interface CloudAccount {
  id: number;
  name: string;
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
}

export interface TeamUser {
  id: number;
  username: string;
  email: string;
  role: string;
  cloudAccounts: CloudAccount[];
  accountIds?: number[];
}

export interface TeamUserForm {
  id: number | null;
  username: string;
  email: string;
  password: string;
  accountIds: number[];
}

export interface PasswordChangeForm {
  oldPassword: string;
  newPassword: string;
  confirmPassword: string;
}
