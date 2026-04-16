/**
 * Authentication and authorization TypeScript types
 */

export type UserRole =
  | 'ROLE_XAMOPS'
  | 'ROLE_ADMIN'
  | 'ROLE_SUPER_ADMIN'
  | 'ROLE_BILLOPS'
  | 'ROLE_BILLOPS_ADMIN'
  | 'ROLE_BILLOPS_ADMIN_SUB';

export interface UserProfile {
  id?: number;
  username?: string;
  email?: string;
  tenantId?: string;
  roles?: UserRole[];
  authorities?: UserRole[]; // Some endpoints return 'authorities' instead of 'roles'
  allowedTabs?: string[];
  isPrimaryUser?: boolean;
}

export interface Tenant {
  id: string;
  name: string;
  // Add other fields based on your API
}

export interface TenantUser {
  id: string;
  username: string;
  email: string;
  // Add other fields based on your API
}
