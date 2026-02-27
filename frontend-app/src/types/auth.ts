/**
 * Authentication and authorization TypeScript types
 */

export type UserRole =
  | 'ROLE_XAMOPS'
  | 'ROLE_ADMIN'
  | 'ROLE_SUPER_ADMIN'
  | 'ROLE_BILLOPS'
  | 'ROLE_BILLOPS_ADMIN';

export interface UserProfile {
  id?: number;
  username?: string;
  email?: string;
  roles?: UserRole[];
  authorities?: UserRole[]; // Some endpoints return 'authorities' instead of 'roles'
  tenantId?: string;        // Backend may return tenant association for regular users
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
